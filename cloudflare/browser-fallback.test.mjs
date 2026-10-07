// Exercise the shipped page with no WebRTC media, using the HTTPS observation lane.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { browserResponse } from './browser-console.js';
import { agentPage } from './browser-agent.js';
const require=createRequire(import.meta.url);
let chromium;try{({chromium}=require('playwright'))}catch{({chromium}=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright'))}
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1000,height:900}}),page=await context.newPage();
const errors=[];page.on('pageerror',e=>errors.push(e.message));
const sid='a'.repeat(64),token='b'.repeat(43),client='0ddfe8bc-a856-4364-a8a0-5e1603ab7c11',commands=[];
const painter=await context.newPage();
const jpeg=await painter.evaluate(()=>{const c=document.createElement('canvas');c.width=360;c.height=720;const p=c.getContext('2d');p.fillStyle='#163955';p.fillRect(0,0,360,720);p.fillStyle='white';p.font='30px sans-serif';p.fillText('HTTPS phone',40,350);return c.toDataURL('image/jpeg').split(',')[1]});
const observation=version=>({observationId:`11111111-1111-4111-8111-${String(version).padStart(12,'0')}`,screenVersion:version,commandNonce:'nonce',display:{width:1080,height:2160},image:{mimeType:'image/jpeg',width:360,height:720,data:jpeg}});
let current=observation(1),instance,result;
await page.route('https://remote.test/**',async route=>{
  const req=route.request(),u=new URL(req.url()),path=u.pathname;
  const reply=json=>route.fulfill({json});
  if(path==='/remote/frame'){assert.equal(u.searchParams.get('observationId'),current.observationId);await route.fulfill({contentType:'image/jpeg',body:Buffer.from(jpeg,'base64')});return}
  if(path.startsWith('/remote')){
    const r=path==='/remote/client.js'?browserResponse(path):await agentPage(u,{ok:true,phoneOnline:true,state:'LIVE',observation:current});
    await route.fulfill({status:r.status,headers:Object.fromEntries(r.headers),body:await r.text()});return;
  }
  if(path.endsWith('/browser-claim')){instance=req.postDataJSON().instanceId;await reply({ok:true,afterSequence:0,browserEpoch:1});return}
  if(path.endsWith('/ice')){await route.fulfill({status:503,json:{error:'TURN_UNAVAILABLE'}});return}
  if(path.endsWith('/agent-observe')){await reply({ok:true,phoneOnline:true,state:'LIVE',observation:current});return}
  if(path.endsWith('/agent-action')){
    assert.equal(req.headers()['x-aaris-instance'],instance);
    const c=req.postDataJSON();assert.equal(c.nonce,'nonce');assert.equal(c.observationId,current.observationId);
    commands.push(c);current=observation(current.screenVersion+1);result={...current,actionId:c.actionId,applied:true};await reply({ok:true,requestId:c.actionId});return;
  }
  if(path.endsWith('/agent-result')){await reply({ok:true,result});return}
  await route.fulfill({status:404,json:{error:'NOT_FOUND'}});
});
try{
  await page.goto(`https://remote.test/remote/?session=${sid}&access=${token}&client=${client}`);
  await page.locator('#fallback').waitFor({state:'visible'});
  await page.waitForFunction(()=>document.getElementById('fallback').naturalWidth===360);
  const rect=await page.locator('#surface').boundingBox();
  await page.mouse.click(rect.x+rect.width/2,rect.y+rect.height/2);
  await page.waitForFunction(()=>document.getElementById('status').textContent.includes('action applied'));
  assert.equal(commands.length,1);assert.equal(commands[0].action,'tap');
  assert.ok(Math.abs(commands[0].x-.5)<.01&&Math.abs(commands[0].y-.5)<.01);
  assert.equal(commands[0].durationMs,undefined,'Tap payload must satisfy the strict Android schema');
  await page.waitForFunction(()=>document.getElementById('fallback').src.includes('000000000002'));
  await page.getByRole('button',{name:'Home',exact:true}).click();
  await page.waitForFunction(()=>document.getElementById('fallback').src.includes('000000000003'));
  assert.equal(commands.length,2);assert.equal(commands[1].action,'home');
  await page.mouse.click(rect.x+5,rect.y+rect.height/2);await page.waitForTimeout(400);
  assert.equal(commands.length,2,'Letterbox clicks must never reach the phone');
  await page.reload();await page.locator('#fallback').waitFor({state:'visible'});
  assert.equal(new URL(page.url()).searchParams.get('client'),client);
  assert.deepEqual(errors,[]);console.log('PASS: no WebRTC video -> HTTPS screenshot -> exact click/Home -> fresh result -> stateless refresh');
}finally{await browser.close()}
