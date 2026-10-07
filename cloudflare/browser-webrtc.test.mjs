// Browser integration test: actual RTP video + SCTP binary input between two
// Chromium pages. The host is a synthetic peer, not an Android device.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { browserResponse } from './browser-console.js';
const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); }
catch { ({ chromium } = require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES + '/playwright')); }
const browser = await chromium.launch({headless:true,...(process.env.BROWSER_TEST_CHROME?{executablePath:process.env.BROWSER_TEST_CHROME}:{})});
const context = await browser.newContext({viewport:{width:1000,height:900}});
const host = await context.newPage(), page = await context.newPage();
const errors=[];page.on('pageerror',error=>errors.push(error.message));
const sid='a'.repeat(64), token='b'.repeat(43), events=[];
let socket, epoch=0, seq=0, ended=false, claimedInstance, claimCursor=0, iceRequests=0;
await host.exposeFunction('publishHost',payload=>{
  const event={seq:++seq,senderRole:'host',...payload};events.push(event);
  socket?.send(JSON.stringify({kind:'signal_event',event}));
});
await host.evaluate(()=>{
  const canvas=document.createElement('canvas');canvas.width=360;canvas.height=720;document.body.append(canvas);
  const paint=canvas.getContext('2d'); let tick=0;
  window.received=[];window.hostEpoch=0;
  setInterval(()=>{paint.fillStyle='#163955';paint.fillRect(0,0,360,720);paint.fillStyle='#8ee9c5';paint.fillRect(30,300,300,110);paint.fillStyle='white';paint.font='22px sans-serif';paint.fillText('Synthetic phone',35,90);paint.fillText(`Frame ${++tick}`,35,140);paint.fillText(`Actions ${window.received.length}`,50,360);},40);
  const track=canvas.captureStream(25).getVideoTracks()[0];
  window.startHost=async epoch=>{
    window.peer?.close();window.hostEpoch=epoch;
    const peer=new RTCPeerConnection({iceServers:[]});window.peer=peer;
    const id=`host:${epoch}`,secret=-9007199254740997n+BigInt(epoch);
    peer.addTrack(track,new MediaStream([track]));
    const dc=peer.createDataChannel('control-v1');peer.createDataChannel('control-telemetry-v1',{ordered:false,maxRetransmits:1});
    dc.binaryType='arraybuffer';
    dc.onopen=()=>{const b=new ArrayBuffer(22),v=new DataView(b);v.setUint8(0,1);v.setUint8(1,1);v.setBigInt64(2,secret);v.setInt32(10,epoch);v.setInt32(14,1080);v.setInt32(18,2160);dc.send(b);};
    dc.onmessage=e=>{
      const v=new DataView(e.data),type=v.getUint8(1);
      if([2,14,15,19,20,21,22,23].includes(type))return;
      if(v.getBigInt64(2)!==secret||v.getInt32(10)!==epoch)throw new Error('Stale lease reached synthetic host');
      const sequence=v.getBigInt64(14);
      window.received.push({epoch,type,sequence:sequence.toString(),x:type===3?v.getUint16(22)/65535:null,y:type===3?v.getUint16(24)/65535:null});
      const b=new ArrayBuffer(11),a=new DataView(b);a.setUint8(0,1);a.setUint8(1,13);a.setBigInt64(2,sequence);a.setUint8(10,1);dc.send(b);
    };
    peer.onicecandidate=e=>{if(e.candidate)window.publishHost({kind:'candidate',payload:{mid:e.candidate.sdpMid,mLine:e.candidate.sdpMLineIndex,sdp:e.candidate.candidate,negotiationId:id}});};
    await peer.setLocalDescription(await peer.createOffer());
    await window.publishHost({kind:'description',payload:{type:'offer',sdp:peer.localDescription.sdp,negotiationId:id}});
  };
  window.answerHost=async(kind,p)=>{
    if(kind==='description')await window.peer.setRemoteDescription({type:'answer',sdp:p.sdp});
    if(kind==='candidate')await window.peer.addIceCandidate({candidate:p.sdp,sdpMid:p.mid,sdpMLineIndex:p.mLine});
  };
});
await page.route('https://remote.test/**',async route=>{
  const req=route.request(),url=new URL(req.url());
  if(url.pathname.startsWith('/remote')){
    const r=browserResponse(url.pathname);await route.fulfill({status:r.status,headers:Object.fromEntries(r.headers),body:await r.text()});return;
  }
  const reply=data=>route.fulfill({json:data});
  if(url.pathname.endsWith('/browser-claim')){
    const instance=req.postDataJSON().instanceId,newDocument=instance!==claimedInstance;
    if(newDocument){claimedInstance=instance;claimCursor=seq;epoch++;}
    await reply({ok:true,afterSequence:claimCursor,browserEpoch:epoch});
    if(newDocument)await host.evaluate(e=>window.startHost(e),epoch);return;
  }
  if(url.pathname.endsWith('/ice')){
    if(++iceRequests===1){await route.fulfill({status:503,json:{error:'temporary_upstream_failure'}});return;}
    await reply({iceServers:[]});return;
  }
  if(url.pathname.endsWith('/events')){
    const {kind,payload}=req.postDataJSON();const event={seq:++seq,senderRole:'controller',kind,payload};events.push(event);
    socket?.send(JSON.stringify({kind:'signal_event',event}));
    await host.evaluate(([k,p])=>window.answerHost(k,p),[kind,payload]);await reply({ok:true,event});return;
  }
  if(url.pathname.endsWith('/close')){ended=true;socket?.send(JSON.stringify({kind:'session',session:{state:'CLOSED'}}));await reply({ok:true});return;}
  await route.fulfill({status:404,json:{error:'NOT_FOUND'}});
});
await page.routeWebSocket('wss://remote.test/**',ws=>{
  socket=ws;const after=Number(new URL(ws.url()).searchParams.get('after')||0);
  ws.send(JSON.stringify({kind:'session',session:{state:'SCREEN_READY'}}));
  for(const event of events.filter(e=>e.seq>after))ws.send(JSON.stringify({kind:'signal_event',event}));
  ws.onMessage(message=>{if(message==='browser_ping')ws.send('pong');});
});
const waitLive=async()=>{
  await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Live'),{},{timeout:20000});
  await page.waitForFunction(()=>document.getElementById('screen').videoWidth>0,{},{timeout:20000});
};
const actionCount=()=>host.evaluate(()=>window.received.length);
const waitActions=async n=>host.waitForFunction(n=>window.received.length>=n,n,{timeout:10000});
try {
  await page.goto(`https://remote.test/remote/#${sid}.${token}`);await waitLive();
  assert.equal(iceRequests,2,'Transient relay configuration failure must recover automatically');
  const rect=await page.locator('#surface').boundingBox();
  await page.mouse.click(rect.x+rect.width/2,rect.y+rect.height/2);await waitActions(1);
  const tap=await host.evaluate(()=>window.received[0]);assert.equal(tap.type,3);assert.ok(Math.abs(tap.x-.5)<.003);assert.ok(Math.abs(tap.y-.5)<.003);
  await page.mouse.click(rect.x+3,rect.y+rect.height/2);await page.waitForTimeout(150);assert.equal(await actionCount(),1,'Letterbox click must not reach phone');
  await page.getByRole('button',{name:'Back',exact:true}).click();await waitActions(2);
  await page.getByRole('button',{name:'Swipe up',exact:true}).click();await waitActions(3);
  await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height*.7);await page.mouse.down();await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height*.3,{steps:12});await page.mouse.up();await waitActions(4);
  await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height/2);await page.mouse.down();await page.waitForTimeout(600);await page.mouse.up();await waitActions(5);
  await page.getByLabel('Text to enter on phone').fill('Hello phone');await page.getByRole('button',{name:'Send text',exact:true}).click();await waitActions(6);
  await page.getByRole('button',{name:'Fresh screenshot',exact:true}).click();await page.locator('#snapshot').waitFor({state:'visible'});
  assert.deepEqual(await host.evaluate(()=>window.received.map(r=>r.type)),[3,6,5,12,4,10]);
  await page.reload();await waitLive();
  await page.getByRole('button',{name:'Home',exact:true}).click();await waitActions(7);
  assert.equal(await host.evaluate(()=>window.received.at(-1).epoch),2,'Refresh must have a new control epoch');
  assert.equal(await host.evaluate(()=>window.received.at(-1).sequence),'1');
  await page.getByRole('button',{name:'Disconnect',exact:true}).click();assert.equal(ended,true);
  assert.deepEqual(errors,[]);
  console.log('PASS: startup retry, actual WebRTC video, binary tap/back/swipe/drag/long press/text, letterbox exclusion, fresh screenshot, refresh and disconnect');
} finally { await browser.close(); }
