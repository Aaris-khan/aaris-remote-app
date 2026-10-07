"""Live signaling test with a synthetic host; creates and closes its own session.
Requires websockets. No phone content or capability tokens are logged.
"""
import asyncio, json, uuid, urllib.request, urllib.error
import websockets
BASE='https://aaris-remote-ice.aaris-remote-wk3548.workers.dev'
def request(path,token=None,data=None,instance=None,expected=200):
    headers={'Content-Type':'application/json','User-Agent':'AarisRemote-Browser-Smoke/1.0'}
    if token: headers['Authorization']='Bearer '+token
    if instance: headers['X-Aaris-Instance']=instance
    req=urllib.request.Request(BASE+path,headers=headers,data=None if data is None else json.dumps(data).encode())
    try: r=urllib.request.urlopen(req,timeout=20)
    except urllib.error.HTTPError as e: r=e
    raw=r.read();assert r.status==expected, (path.split('/')[-1],r.status,raw[:120])
    return json.loads(raw)
async def match(ws,predicate):
    for _ in range(30):
        data=await asyncio.wait_for(ws.recv(),8)
        if data=='pong': continue
        d=json.loads(data)
        if predicate(d):return d
    raise AssertionError('Expected socket event missing')
async def main():
    with urllib.request.urlopen(urllib.request.Request(BASE+'/remote/',headers={'User-Agent':'AarisRemote-Browser-Smoke/1.0'}),timeout=20) as r:
        assert r.status==200 and r.headers['Referrer-Policy']=='no-referrer'
        assert b'autoplay muted playsinline' in r.read()
    session=request('/v1/browser-sessions',data={},expected=201)
    sid,host,token=(session[k] for k in ('sessionId','hostToken','controllerToken'))
    assert host!=token
    path='/v1/sessions/'+sid
    client,instance=str(uuid.uuid4()),str(uuid.uuid4())
    req=lambda action,data=None,t=token,i=instance,expected=200:request(path+action,t,data,i,expected)
    try:
        claim=req('/browser-claim',{'clientId':client,'instanceId':instance})
        assert claim['browserEpoch']==1
        print('PASS: page and browser claim',flush=True)
        assert req('/browser-claim',{'clientId':client,'instanceId':instance})['browserEpoch']==1
        req('/browser-claim',{'clientId':str(uuid.uuid4()),'instanceId':str(uuid.uuid4())},expected=409)
        req('/state',{'target':'SCREEN_READY'},t=host,i=None)
        url=BASE.replace('https:','wss:')+path+'/socket?after=0'
        async with websockets.connect(url,additional_headers={'Authorization':'Bearer '+host},user_agent_header='AarisRemote-Browser-Smoke/1.0',open_timeout=20,close_timeout=3) as hw:
            async with websockets.connect(url,origin=BASE,subprotocols=['aaris-remote','auth.'+token,'instance.'+instance],user_agent_header='AarisRemote-Browser-Smoke/1.0',open_timeout=20,close_timeout=3) as bw:
                assert bw.subprotocol=='aaris-remote'
                print('PASS: native host and browser WebSocket handshake',flush=True)
                await match(hw,lambda d:d.get('kind')=='session')
                await match(bw,lambda d:d.get('kind')=='session')
                await bw.send('browser_ping')
                await asyncio.to_thread(req,'/events',{'kind':'description','payload':{'type':'offer','sdp':'v=0\r\n','negotiationId':'browser-live-smoke:1'}},host,None,201)
                await match(bw,lambda d:d.get('event',{}).get('kind')=='description')
                await asyncio.to_thread(req,'/events',{'kind':'description','payload':{'type':'answer','sdp':'v=0\r\n','negotiationId':'browser-answer:1','replyToNegotiationId':'browser-live-smoke:1'}},token,instance,201)
                await match(hw,lambda d:d.get('event',{}).get('senderRole')=='controller')
                ice=await asyncio.to_thread(req,'/ice',{})
                urls=[u for s in ice['iceServers'] for u in (s['urls'] if isinstance(s['urls'],list) else [s['urls']])]
                assert any(u.startswith(('turn:','turns:')) for u in urls)
                print('PASS: offer, answer and TURN credentials',flush=True)
                await asyncio.to_thread(req,'/state',{'target':'LIVE'},host,None)
                replacement=str(uuid.uuid4())
                new=await asyncio.to_thread(req,'/browser-claim',{'clientId':client,'instanceId':replacement})
                assert new['browserEpoch']==2
                await asyncio.to_thread(req,'',None,token,instance,409)
                next_url=BASE.replace('https:','wss:')+path+'/socket?after='+str(new['afterSequence'])
                async with websockets.connect(next_url,origin=BASE,subprotocols=['aaris-remote','auth.'+token,'instance.'+replacement],user_agent_header='AarisRemote-Browser-Smoke/1.0',open_timeout=20,close_timeout=3) as nw:
                    await match(nw,lambda d:d.get('kind')=='session' and d['session']['browserEpoch']==2)
                    await nw.send('browser_ping')
                    await asyncio.to_thread(req,'/close',{},host,None)
                    await match(nw,lambda d:d.get('session',{}).get('state')=='CLOSED')
                await asyncio.to_thread(req,'',None,token,replacement,410)
        print('PASS: deployed console, new link, one controller, native/browser WebSockets, SDP, TURN, LIVE, refresh fencing and STOP revocation')
    finally:
        try: request(path+'/close',host,{})
        except Exception:pass
asyncio.run(main())
