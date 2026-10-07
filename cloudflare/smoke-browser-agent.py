"""Production HTTPS/WS observation test with a synthetic host, never a real phone.
Creates/revokes its own session. Capability values and frame content are not logged.
"""
import asyncio, base64, json, uuid, urllib.request, urllib.error
import websockets
BASE = 'https://aaris-remote-ice.aaris-remote-wk3548.workers.dev'
USER_AGENT = 'AarisRemote-Browser-Smoke/1.0'
# Synthetic JPEG marker; rendering/valid JPEG coverage lives in the Chromium test.
FRAME = base64.b64encode(b'\xff\xd8synthetic-frame\xff\xd9').decode()
def call(path, token=None, data=None, instance=None, expected=200, raw=False):
    headers = {'User-Agent': USER_AGENT, 'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer '+token
    if instance: headers['X-Aaris-Instance'] = instance
    request = urllib.request.Request(BASE+path, headers=headers, data=None if data is None else json.dumps(data).encode())
    try: response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error: response = error
    body = response.read()
    assert response.status == expected, (path.split('?')[0].split('/')[-1], response.status)
    return (body, response.headers) if raw else json.loads(body)
async def receive(ws):
    for _ in range(20):
        message = json.loads(await asyncio.wait_for(ws.recv(), 12))
        if message.get('kind') == 'agent_request': return message
    raise AssertionError('No host request')
async def main():
    session = await asyncio.to_thread(call, '/v1/browser-sessions', data={}, expected=201)
    sid, host, token = (session[k] for k in ('sessionId','hostToken','controllerToken'))
    base = '/v1/sessions/'+sid
    instance = str(uuid.uuid4())
    observation_id = str(uuid.uuid4())
    snapshot = {'observationId':observation_id,'screenVersion':1,'commandNonce':str(uuid.uuid4()),
                'currentPackage':'synthetic.phone','image':{'mimeType':'image/jpeg','width':360,'height':720,'data':FRAME}}
    query = '?session='+sid+'&access='+token+'&client='+session['browserClientId']
    try:
        html, _ = await asyncio.to_thread(call, '/remote/'+query, raw=True)
        assert b'Phone offline' in html
        state = await asyncio.to_thread(call, base, host)
        assert state['session']['browserEpoch'] == 0
        await asyncio.to_thread(call, base+'/state', host, {'target':'SCREEN_READY'})
        socket_url = BASE.replace('https:','wss:')+base+'/agent-socket'
        async with websockets.connect(socket_url, additional_headers={'Authorization':'Bearer '+host}, user_agent_header=USER_AGENT, open_timeout=20, close_timeout=3) as ws:
            await asyncio.to_thread(call, base+'/agent-publish', host, {'result':snapshot})
            html, _ = await asyncio.to_thread(call, '/remote/'+query, raw=True)
            assert b'AARIS_ACTION_BEGIN' in html and b'View current phone screenshot' in html
            state = await asyncio.to_thread(call, base, host)
            assert state['session']['browserEpoch'] == 0, 'A preview must not claim the browser'
            image, headers = await asyncio.to_thread(call, '/remote/frame'+query+'&observationId='+observation_id, raw=True)
            assert image == base64.b64decode(FRAME) and headers['Content-Type'] == 'image/jpeg'
            assert headers['Cache-Control'].startswith('no-store')
            print('PASS: deployed server-readable status/JPEG without JS or controller claim', flush=True)
            await asyncio.to_thread(call, base+'/browser-claim', token, {'clientId':session['browserClientId'],'instanceId':instance}, instance)
            command = {'actionId':str(uuid.uuid4()),'action':'tap','x':0.5,'y':0.5,'observationId':observation_id,'screenVersion':1,'nonce':snapshot['commandNonce'],'protocol':'aaris-link-v1'}
            queued = await asyncio.to_thread(call, base+'/agent-action', token, command, instance, 202)
            delivered = await receive(ws)
            assert delivered['requestId'] == queued['requestId'] and delivered['args'] == command
            await asyncio.to_thread(call, base+'/agent-start', host, {'requestId':queued['requestId']})
            await asyncio.to_thread(call, base+'/agent-start', host, {'requestId':queued['requestId']}, expected=409)
            after = {**snapshot, 'observationId':str(uuid.uuid4()), 'screenVersion':2, 'actionId':command['actionId'], 'applied':True}
            await asyncio.to_thread(call, base+'/agent-publish', host, {'result':after,'requestId':queued['requestId']})
            result = await asyncio.to_thread(call, base+'/agent-result?requestId='+queued['requestId'], token, instance=instance)
            assert result['result']['applied'] is True and result['result']['screenVersion'] == 2
            duplicate = await asyncio.to_thread(call, base+'/agent-action', token, command, instance, 202)
            assert duplicate['result']['applied'] is True
            await asyncio.to_thread(call, '/remote/frame'+query+'&observationId='+observation_id, expected=409, raw=True)
            print('PASS: production action delivery, atomic start, synthetic ACK, fresh observation and deduplication', flush=True)
            replacement = str(uuid.uuid4())
            await asyncio.to_thread(call, base+'/browser-claim', token, {'clientId':session['browserClientId'],'instanceId':replacement}, replacement)
            await asyncio.to_thread(call, base+'/agent-action', token, command, instance, 409)
            await asyncio.to_thread(call, base+'/close', host, {})
            await asyncio.to_thread(call, '/remote/frame'+query+'&observationId='+after['observationId'], expected=410, raw=True)
            print('PASS: replaced controller fenced and STOP revokes the screenshot', flush=True)
    finally:
        try: await asyncio.to_thread(call, base+'/close', host, {})
        except Exception: pass
asyncio.run(main())
