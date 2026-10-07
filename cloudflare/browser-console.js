import { createBrowserProtocol } from './browser-protocol.js';

// This function is served as an ES module. Keep all browser dependencies inside
// it so the Worker needs neither a UI framework nor a separate asset deployment.
export function browserMain(protocolFactory) {
  const wire = protocolFactory(), $ = id => document.getElementById(id);
  const video = $('screen'), status = $('status'), surface = $('surface'), shot = $('snapshot'), fallback = $('fallback');
  const aiCommand = $('aiCommand'), aiExecute = $('aiExecute'), aiTargets = $('aiTargets'), aiResult = $('aiResult');
  const query = new URLSearchParams(location.search);
  const linkValue = query.has('session') ? `${query.get('session')}.${query.get('access')}${query.get('client') ? '.' + query.get('client') : ''}` : location.hash.slice(1);
  const match = linkValue.match(/^([a-f0-9]{64})\.([A-Za-z0-9_-]{43})(?:\.([a-f0-9-]{36}))?$/);
  let stopped = false, ws, pc, control, hello, sequence = 0n, after = 0;
  let remoteId, localId, localReady = false, localCandidates = [], remoteCandidates = new Map();
  let signalChain = Promise.resolve(), publishChain = Promise.resolve(), reconnectTimer, retry = 0;
  let busy = false, pending = null, pointer = null, lastFrameAt = 0, rendered = 0, videoReadyGeneration = -1;
  let lastStats, lastPresented = 0, telemetrySequence = 0n, telemetry;
  let observation, observationAt = 0, snapshotPending = false;
  const seenSignals = new Set();
  const timers = [], instanceId = crypto.randomUUID();
  let clientId;
  const message = text => { if (status.textContent !== text) status.textContent = text; };
  function invalidateControls() {
    hello = null; pointer = null; videoReadyGeneration = -1;
    if (pending) { clearTimeout(pending.timer); pending.reject(new Error('Connection changed; observe before trying again')); pending = null; }
    busy = snapshotPending;
  }
  function end(text) {
    stopped = true; clearTimeout(reconnectTimer); fallback.removeAttribute('src'); fallback.hidden = true; timers.forEach(clearInterval); invalidateControls();
    ws?.close(); pc?.close(); video.srcObject = null; shot.removeAttribute('src'); message(text);
  }
  if (!match) { end('Open the complete link copied by Aaris Remote on your phone.'); return; }
  const [, sessionId, capability, linkClientId] = match;
  clientId = linkClientId || crypto.randomUUID();
  if (!linkClientId) {
    try {
      const key = `aaris-browser-${sessionId}`;
      clientId = sessionStorage.getItem(key) || clientId; sessionStorage.setItem(key, clientId);
    } catch { /* Legacy links still connect once when browser storage is unavailable. */ }
    // Canonicalize old two-part links immediately. Stateless/cloud browsers can
    // retain the upgraded URL and reclaim the same controller identity later.
    try { history.replaceState(null, '', query.has('session') ? `?session=${sessionId}&access=${capability}&client=${clientId}` : `#${sessionId}.${capability}.${clientId}`); } catch {}
  }
  const base = `/v1/sessions/${sessionId}`;
  async function api(action, data) {
    const response = await fetch(base + (action ? `/${action}` : ''), {
      method: data === undefined ? 'GET' : 'POST', cache: 'no-store', credentials: 'omit',
      headers: { Authorization: `Bearer ${capability}`, 'Content-Type': 'application/json', 'X-Aaris-Instance': instanceId },
      body: data === undefined ? undefined : JSON.stringify(data), signal: AbortSignal.timeout(12000)
    });
    const value = await response.json();
    if (!response.ok) {
      if ([401, 403, 410].includes(response.status)) end('This session ended. Create a new link on the phone.');
      if (value.error === 'controller_busy' || value.error === 'controller_replaced') end('This link is already open in another controller.');
      throw new Error(value.message || value.error || 'Connection unavailable');
    }
    return value;
  }
  function publish(kind, payload) {
    // Only signaling is queued. Input is never queued or replayed after failure.
    const operation = publishChain.then(() => { if (!stopped) return api('events', { kind, payload }); });
    publishChain = operation.catch(() => {}); return operation;
  }
  function short(packet) {
    if (control?.readyState === 'open' && control.bufferedAmount < 4096) control.send(packet);
  }
  function frame() {
    lastFrameAt = performance.now(); rendered++;
    if (fresh() && !busy) { fallback.hidden = true; video.hidden = false; message('Live • click the phone screen to control it'); }
    if (hello && hello.generation !== videoReadyGeneration) {
      short(wire.videoReady(hello)); videoReadyGeneration = hello.generation;
    }
  }
  if (video.requestVideoFrameCallback) {
    const onFrame = () => { if (stopped) return; frame(); video.requestVideoFrameCallback(onFrame); };
    video.requestVideoFrameCallback(onFrame);
  } else video.addEventListener('timeupdate', frame);
  function fresh() {
    return !stopped && hello && control?.readyState === 'open' && video.readyState >= 2 &&
      performance.now() - lastFrameAt < 2500 && video.videoWidth > 0 && video.videoHeight > 0 &&
      Math.abs(video.videoWidth / video.videoHeight - hello.width / hello.height) < 0.04;
  }
  async function act(action, args = {}) {
    if (!fresh()) return snapshotAct(action, args);
    if (busy || control.bufferedAmount > 4096) throw new Error('Wait for the current action to finish');
    const seq = sequence + 1n, packet = wire.command(action, hello, seq, args);
    sequence = seq; busy = true;
    try {
      const outcome = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { pending = null; reject(new Error('Action outcome unknown. Inspect the screen before retrying.')); }, 6500);
        pending = { seq, resolve, reject, timer };
        try { control.send(packet); } catch (error) { clearTimeout(timer); pending = null; reject(error); }
      });
      message(outcome ? 'Live • action applied' : 'Action was not applied • inspect the phone');
      return { applied: outcome };
    } finally { busy = false; }
  }
  function normalizeLabel(value) {
    return String(value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase();
  }
  function semanticTargets(value = observation) {
    const width = Number(value?.display?.width), height = Number(value?.display?.height);
    if (!(width > 0 && height > 0) || !Array.isArray(value?.uiHints)) return [];
    return value.uiHints.flatMap((hint, index) => {
      const b = hint?.bounds;
      if (!Array.isArray(b) || b.length !== 4 || !b.every(Number.isFinite)) return [];
      const [left, top, right, bottom] = b.map(Number);
      if (!(right > left && bottom > top)) return [];
      const label = String(hint.text || hint.contentDescription || '').trim();
      if (!label) return [];
      return [{
        index,
        label,
        role: String(hint.role || ''),
        clickable: hint.clickable === true || hint.editable === true,
        enabled: hint.enabled !== false,
        x: ((left + right) / 2 / width),
        y: ((top + bottom) / 2 / height)
      }];
    }).filter(target => target.enabled && target.clickable && target.x >= 0 && target.x <= 1 && target.y >= 0 && target.y <= 1);
  }
  function renderSemanticTargets() {
    if (!aiTargets) return;
    const targets = semanticTargets();
    aiTargets.textContent = targets.length
      ? targets.slice(0, 40).map(t => `${t.label}${t.role ? ' [' + t.role + ']' : ''}`).join(' • ')
      : 'No named Accessibility targets on the current screen. Use normalized x/y coordinates instead.';
  }
  function setObservation(value) {
    if (!value?.observationId || !value.image || pointer || busy) return;
    if (value.observationId === observation?.observationId) { renderSemanticTargets(); showFallback(); return; }
    observation = value; observationAt = performance.now();
    renderSemanticTargets();
    const params = new URLSearchParams({ session: sessionId, access: capability, observationId: value.observationId });
    fallback.src = `/remote/frame?${params}`;
    fallback.onload = showFallback;
  }
  function showFallback() {
    if (!stopped && !fresh() && observation && fallback.complete && fallback.naturalWidth > 0) {
      video.hidden = true; fallback.hidden = false;
      message(observation.redacted ? 'Protected/setup screen • use Home to open the launcher' : 'Screenshot mode • click the phone screen to control it');
    }
  }
  function snapshotFresh() {
    return !stopped && observation && performance.now() - observationAt < 85000 &&
      !fallback.hidden && fallback.complete && fallback.naturalWidth > 0;
  }
  async function pollResult(requestId) {
    const until = performance.now() + 14000;
    while (!stopped && performance.now() < until) {
      const value = await api(`agent-result?requestId=${encodeURIComponent(requestId)}`);
      if (value.result) return value.result;
      await new Promise(resolve => setTimeout(resolve, 300));
    }
    throw new Error('Action outcome unknown. Inspect a fresh screenshot before trying again.');
  }
  async function snapshotAct(action, args) {
    if (!snapshotFresh()) throw new Error('Wait for a fresh phone screenshot');
    if (busy) throw new Error('Wait for the current action to finish');
    const mapped = { longPress: 'long_press', text: 'type' }[action] || action;
    const input = { ...args };
    if (mapped === 'tap') delete input.durationMs;
    if (input.points?.length > 24) input.points = Array.from({length:24}, (_, i) => input.points[Math.round(i * (input.points.length - 1) / 23)]);
    if (input.durationMs !== undefined) input.durationMs = Math.round(Math.max(80, Math.min(1500, input.durationMs)));
    const command = { protocol:'aaris-link-v1', nonce:observation.commandNonce, actionId:crypto.randomUUID(),
      observationId:observation.observationId, screenVersion:observation.screenVersion, action:mapped, ...input };
    busy = true; snapshotPending = true;
    try {
      const queued = await api('agent-action', command), result = queued.result || await pollResult(queued.requestId);
      busy = false;
      if (result.observationId) setObservation(result);
      if (result.error) throw new Error(result.error);
      message(result.applied === true ? 'Screenshot mode • action applied' : result.applied === false ? 'Action was not applied • inspect the phone' : 'Action outcome unknown • inspect the phone');
      return result;
    } finally { snapshotPending = false; busy = false; }
  }
  async function getActionObservation() {
    if (busy) throw new Error('Wait for the current action to finish');
    const state = await api('agent-observe');
    if (state.observation?.observationId) {
      setObservation(state.observation);
      return state.observation;
    }
    const queued = await api('agent-refresh', {});
    const result = queued.result || await pollResult(queued.requestId);
    if (!result?.observationId) throw new Error(result?.error || 'Fresh phone observation unavailable');
    setObservation(result);
    return result;
  }
  function resolveNamedTarget(command, value) {
    const requested = normalizeLabel(command.target ?? command.label);
    if (!requested) throw new Error('tap requires "target" or normalized x/y');
    const targets = semanticTargets(value);
    const exact = targets.filter(t => normalizeLabel(t.label) === requested);
    const prefix = exact.length ? exact : targets.filter(t => normalizeLabel(t.label).startsWith(requested));
    const contains = prefix.length ? prefix : targets.filter(t => normalizeLabel(t.label).includes(requested));
    if (contains.length !== 1) {
      const names = contains.length ? contains : targets;
      const sample = names.slice(0, 12).map(t => t.label).join(', ');
      throw new Error(contains.length > 1
        ? `Target "${command.target ?? command.label}" is ambiguous: ${sample}`
        : `Target "${command.target ?? command.label}" not found. Visible targets: ${sample || 'none'}`);
    }
    return { x: contains[0].x, y: contains[0].y };
  }
  function compileAiCommand(command, value) {
    if (!command || typeof command !== 'object' || Array.isArray(command)) throw new Error('AI command must be one JSON object');
    const action = String(command.action || '').trim().toLowerCase().replace(/-/g, '_');
    if (!action) throw new Error('Missing action');

    if (action === 'tap') {
      if (command.target !== undefined || command.label !== undefined) {
        return { action: 'tap', args: resolveNamedTarget(command, value) };
      }
      const x = Number(command.x), y = Number(command.y);
      if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1)) throw new Error('tap x/y must be normalized 0..1');
      return { action: 'tap', args: { x, y } };
    }
    if (action === 'long_press' || action === 'longpress') {
      const point = (command.target !== undefined || command.label !== undefined)
        ? resolveNamedTarget(command, value)
        : { x: Number(command.x), y: Number(command.y) };
      if (!(point.x >= 0 && point.x <= 1 && point.y >= 0 && point.y <= 1)) throw new Error('long_press needs target or normalized x/y');
      return { action: 'longPress', args: { ...point, durationMs: Number(command.durationMs || 650) } };
    }
    if (action === 'type' || action === 'text') {
      const text = String(command.text ?? '');
      if (!text) throw new Error('type requires non-empty text');
      return { action: 'text', args: { text } };
    }
    if (action === 'back' || action === 'home' || action === 'recents') return { action, args: {} };
    if (action === 'swipe') {
      const direction = String(command.direction || '').toLowerCase();
      if (direction) {
        const map = {
          up: { x:.5, y:.78, toX:.5, toY:.22 },
          down: { x:.5, y:.22, toX:.5, toY:.78 },
          left: { x:.82, y:.5, toX:.18, toY:.5 },
          right: { x:.18, y:.5, toX:.82, toY:.5 }
        };
        if (!map[direction]) throw new Error('swipe direction must be up/down/left/right');
        return { action:'swipe', args:{ ...map[direction], durationMs:Number(command.durationMs || 320) } };
      }
      const args = {
        x:Number(command.x), y:Number(command.y), toX:Number(command.toX), toY:Number(command.toY),
        durationMs:Number(command.durationMs || 320)
      };
      if (![args.x,args.y,args.toX,args.toY].every(v => v >= 0 && v <= 1)) throw new Error('swipe coordinates must be normalized 0..1');
      return { action:'swipe', args };
    }
    if (action === 'open_app') {
      const app = String(command.app || '').trim();
      if (!app) throw new Error('open_app requires Android package name in "app"');
      return { action:'open_app', args:{ app } };
    }
    throw new Error(`Unsupported action "${action}"`);
  }
  async function executeAiJson() {
    if (!aiCommand) return;
    let command;
    try { command = JSON.parse(aiCommand.value); }
    catch { throw new Error('Invalid JSON'); }
    const value = await getActionObservation();
    const compiled = compileAiCommand(command, value);
    /*
     * Deliberately use the observation-backed agent path even when live WebRTC
     * control is available. It reuses the MCP-class validation/executor instead
     * of trusting a stale browser coordinate.
     */
    const result = await snapshotAct(compiled.action, compiled.args);
    if (aiResult) aiResult.textContent = result?.applied === true ? 'Applied' : result?.error || 'Outcome unknown';
    return result;
  }
  if (aiExecute) aiExecute.onclick = () => executeAiJson().catch(error => {
    if (aiResult) aiResult.textContent = error.message || 'Command failed';
    report(error);
  });
  if (aiCommand) aiCommand.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault();
      aiExecute?.click();
    }
  });

  async function updateSnapshot() {
    if (stopped || fresh() || busy || pointer || document.hidden) return;
    try {
      const state = await api('agent-observe');
      if (state.observation) setObservation(state.observation);
      else message(!state.phoneOnline ? 'Phone offline • finish screen sharing on the phone' : state.error === 'SCREENSHOT_ADAPTER_UNAVAILABLE' ? 'Screenshot adapter unavailable • update Aaris on the phone' : state.error === 'DEVICE_LOCKED_OR_ACCESSIBILITY_OFF' ? 'Unlock the phone or restore Accessibility' : state.error && state.error !== 'OBSERVATION_PENDING' ? `Phone screenshot unavailable • ${state.error}` : 'Phone connected • preparing screenshot…');
    } catch (error) { if (!stopped && !fresh()) message('Waiting for phone screenshot • reconnecting automatically…'); }
  }
  try { setObservation(JSON.parse($('initialObservation')?.dataset.value || 'null')); } catch {}
  function report(error) { if (!stopped) message(error.message || 'Connection recovering…'); }
  const run = (action, args) => act(action, args).catch(report);
  function bindChannel(channel) {
    if (channel.label === 'control-telemetry-v1') { telemetry = channel; return; }
    if (channel.label !== 'control-v1') return;
    control = channel; channel.binaryType = 'arraybuffer';
    channel.onmessage = event => {
      let packet; try { packet = wire.decode(event.data); } catch { return; }
      if (packet?.type === 'hello') {
        if (packet.width < 1 || packet.height < 1) return;
        invalidateControls(); hello = packet; sequence = 0n; short(wire.heartbeat(hello));
        message(fresh() ? 'Live • click the phone screen to control it' : 'Control connected • waiting for video or screenshot…');
      } else if (packet?.type === 'result' && pending?.seq === packet.sequence) {
        const p = pending; pending = null; clearTimeout(p.timer); p.resolve(packet.applied);
      }
    };
    channel.onclose = () => { if (control === channel) { invalidateControls(); if (!stopped) message('Reconnecting to phone…'); } };
  }
  async function connectPeer() {
    const ice = await api('ice', {});
    if (stopped) return;
    pc?.close();
    pc = new RTCPeerConnection({ iceServers: ice.iceServers, bundlePolicy: 'max-bundle' });
    pc.ondatachannel = event => bindChannel(event.channel);
    pc.ontrack = event => {
      if (event.track.kind !== 'video') return;
      video.srcObject = new MediaStream([event.track]);
      video.play().catch(() => { $('play').hidden = false; message('Browser paused video • press Show phone'); });
    };
    pc.onicecandidate = event => {
      const c = event.candidate; if (!c) return;
      const payload = { mid: c.sdpMid, mLine: c.sdpMLineIndex, sdp: c.candidate, negotiationId: localId };
      if (!localReady) localCandidates.push(payload); else publish('candidate', payload).catch(report);
    };
    pc.onconnectionstatechange = () => {
      if (['failed', 'disconnected'].includes(pc.connectionState)) {
        invalidateControls(); message('Network changed • reconnecting…');
        publish('ice_restart', { requestId: crypto.randomUUID() }).catch(report);
      }
    };
  }
  async function signal(event) {
    if (!Number.isSafeInteger(event.seq) || event.seq <= after || seenSignals.has(event.seq)) return;
    seenSignals.add(event.seq);
    while (seenSignals.delete(after + 1)) after++;
    if (event.senderRole !== 'host') return;
    const p = event.payload;
    if (event.kind === 'description' && p.type === 'offer') {
      if (remoteId === p.negotiationId) return;
      remoteId = p.negotiationId; localId = `${instanceId}:${Date.now()}`; localReady = false; localCandidates = [];
      await pc.setRemoteDescription({ type: 'offer', sdp: p.sdp });
      for (const candidate of remoteCandidates.get(remoteId) || []) await pc.addIceCandidate(candidate);
      remoteCandidates = new Map();
      await pc.setLocalDescription(await pc.createAnswer());
      await publish('description', { type: 'answer', sdp: pc.localDescription.sdp, negotiationId: localId, replyToNegotiationId: remoteId });
      localReady = true;
      for (const candidate of localCandidates) await publish('candidate', candidate);
      localCandidates = [];
    } else if (event.kind === 'candidate') {
      const candidate = { candidate: p.sdp, sdpMid: p.mid, sdpMLineIndex: p.mLine };
      if (p.negotiationId === remoteId && pc.remoteDescription) await pc.addIceCandidate(candidate);
      else if (remoteCandidates.size < 8) {
        const list = remoteCandidates.get(p.negotiationId) || [];
        if (list.length < 128) list.push(candidate);
        remoteCandidates.set(p.negotiationId, list);
      }
    }
  }
  function socket() {
    if (stopped) return;
    const next = new WebSocket(`${location.origin.replace('https:', 'wss:').replace('http:', 'ws:')}${base}/socket?after=${after}`,
      ['aaris-remote', `auth.${capability}`, `instance.${instanceId}`]);
    ws = next;
    next.onopen = () => { retry = 0; };
    next.onmessage = event => {
      if (event.data === 'pong' || ws !== next) return;
      let data; try { data = JSON.parse(event.data); } catch { return; }
      if (data.kind === 'session' && data.session?.state === 'CLOSED') { end('Session ended on phone. Create a new link to connect again.'); return; }
      if (data.kind === 'signal_event') signalChain = signalChain.then(() => signal(data.event)).catch(report);
    };
    next.onclose = event => {
      if (stopped || ws !== next) return;
      if ([4003, 4009].includes(event.code)) { end(event.code === 4009 ? 'Controller replaced by another tab.' : 'Session ended on phone.'); return; }
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(socket, Math.min(8000, 500 * 2 ** Math.min(retry++, 4)));
    };
  }
  // Keyboard/DOM controls are real page controls: automation needs no arbitrary
  // JS execution or special browser extension to use this console.
  document.querySelectorAll('[data-action]').forEach(button => button.onclick = () => run(button.dataset.action));
  document.querySelectorAll('[data-swipe]').forEach(button => button.onclick = () => {
    const up = button.dataset.swipe === 'up'; run('swipe', { x: .5, y: up ? .75 : .25, toX: .5, toY: up ? .25 : .75, durationMs: 350 });
  });
  $('sendText').onclick = () => run('text', { text: $('text').value });
  $('tapPoint').onclick = () => run('tap', { x: Number($('x').value), y: Number($('y').value) });
  $('play').onclick = () => video.play().then(() => { $('play').hidden = true; }).catch(report);
  $('refresh').onclick = () => {
    if (!fresh()) {
      if (busy) return;
      api('agent-refresh', {}).then(q => pollResult(q.requestId)).then(setObservation).catch(report); return;
    }
    if (!hello) { message('Waiting for phone…'); return; }
    short(wire.refresh(hello));
    const before = rendered, start = performance.now();
    const capture = () => {
      if (stopped) return;
      if (rendered <= before && performance.now() - start < 2000) { requestAnimationFrame(capture); return; }
      if (rendered <= before || !fresh()) { message('No fresh frame yet • wait for the connection'); return; }
      const canvas = document.createElement('canvas'); canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      canvas.getContext('2d').drawImage(video, 0, 0); shot.src = canvas.toDataURL('image/jpeg', .9); shot.hidden = false;
      message('Fresh frame captured • live screen above remains interactive');
    };
    requestAnimationFrame(capture);
  };
  $('disconnect').onclick = async () => { try { await api('close', {}); } catch (error) { report(error); } finally { end('Disconnected. Create a new link on the phone.'); } };
  const point = event => wire.point(event.clientX, event.clientY, surface.getBoundingClientRect(), fresh() ? video.videoWidth : fallback.naturalWidth, fresh() ? video.videoHeight : fallback.naturalHeight);
  surface.onpointerdown = event => {
    if ((!fresh() && !snapshotFresh()) || busy || event.button !== 0 || pointer) return;
    const p = point(event); if (!p) return;
    pointer = { id: event.pointerId, at: performance.now(), hello, observation, points: [p], x: event.clientX, y: event.clientY, distance: 0 };
    surface.setPointerCapture(event.pointerId); event.preventDefault();
  };
  surface.onpointermove = event => {
    if (!pointer || pointer.id !== event.pointerId) return;
    const p = point(event); if (!p) return;
    pointer.distance = Math.max(pointer.distance, Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y));
    if (pointer.points.length < 95) pointer.points.push(p); else pointer.points[94] = p;
  };
  surface.onpointerup = event => {
    const p = pointer; pointer = null;
    if (!p || p.id !== event.pointerId || (fresh() ? p.hello !== hello : p.observation !== observation)) return;
    const last = point(event); if (!last) return;
    const durationMs = performance.now() - p.at;
    if (p.distance < 7 && Math.hypot(event.clientX - p.x, event.clientY - p.y) < 7) run(durationMs >= 500 ? 'longPress' : 'tap', { ...last, durationMs });
    else run('drag', { points: [...p.points, last], durationMs });
  };
  surface.onpointercancel = () => { pointer = null; };
  surface.oncontextmenu = event => event.preventDefault();
  surface.onwheel = event => { event.preventDefault(); if (!busy) run('swipe', { x: .5, y: event.deltaY > 0 ? .75 : .25, toX: .5, toY: event.deltaY > 0 ? .25 : .75, durationMs: 300 }); };
  // Application-level feedback shares Android's existing telemetry channel.
  // Rendered frame callbacks, rather than video playback time, prove freshness.
  function feedback(type, values = [], floats = []) {
    if (!hello) return;
    const b = new ArrayBuffer(22 + values.length * 4), v = new DataView(b);
    v.setUint8(0, 1); v.setUint8(1, type); v.setBigInt64(2, hello.secret); v.setInt32(10, hello.generation); v.setBigInt64(14, ++telemetrySequence);
    values.forEach((value, i) => floats.includes(i) ? v.setFloat32(22 + i * 4, value) : v.setInt32(22 + i * 4, value));
    const dc = telemetry?.readyState === 'open' ? telemetry : control;
    if (dc?.readyState === 'open' && dc.bufferedAmount < 4096) dc.send(b);
  }
  timers.push(setInterval(() => {
    if (hello) short(wire.heartbeat(hello));
    if (ws?.readyState === WebSocket.OPEN) ws.send('browser_ping');
  }, 5000));
  timers.push(setInterval(async () => {
    if (!pc || stopped || !hello || document.hidden) return;
    if (rendered > lastPresented) { feedback(21); lastPresented = rendered; }
    try {
      const reports = await pc.getStats();
      reports.forEach(r => {
        if (r.type !== 'inbound-rtp' || r.kind !== 'video') return;
        if (lastStats?.id === r.id) {
          const ms = Math.round(r.timestamp - lastStats.timestamp), count = r.framesDecoded - lastStats.framesDecoded;
          const emitted = (r.jitterBufferEmittedCount || 0) - (lastStats.jitterBufferEmittedCount || 0);
          if (ms >= 500 && ms <= 6000 && count >= 0 && count <= 2048) {
            feedback(19, [ms, count, count ? ((r.totalDecodeTime || 0) - (lastStats.totalDecodeTime || 0)) * 1000 / count : -1,
              emitted ? ((r.jitterBufferDelay || 0) - (lastStats.jitterBufferDelay || 0)) * 1000 / emitted : -1,
              Math.max(0, (r.framesDropped || 0) - (lastStats.framesDropped || 0)), Math.max(0, (r.freezeCount || 0) - (lastStats.freezeCount || 0))], [2, 3]);
            $('metrics').textContent = `${Math.round(count * 1000 / ms)} fps • ${r.frameWidth || video.videoWidth} × ${r.frameHeight || video.videoHeight}`;
          }
        }
        lastStats = r;
      });
    } catch { /* Stats are advisory, never a session-ending condition. */ }
  }, 1000));
  document.addEventListener('visibilitychange', () => {
    pointer = null;
    if (!document.hidden && hello) { short(wire.heartbeat(hello)); short(wire.refresh(hello)); }
  });
  // Deliberately no unload disconnect: refresh must be able to resume the link.
  message('Connecting live video • preparing screenshot fallback…');
  updateSnapshot();
  timers.push(setInterval(updateSnapshot, 2500));
  async function start() {
    try {
      const claimed = await api('browser-claim', { clientId, instanceId }); after = claimed.afterSequence;
      if (stopped) return;
      await connectPeer(); if (!stopped) socket();
    } catch (error) {
      if (stopped) return;
      message('Connection unavailable • retrying automatically…');
      reconnectTimer = setTimeout(start, Math.min(8000, 500 * 2 ** Math.min(retry++, 4)));
    }
  }
  start();
}

export function browserResponse(path) {
  const headers = { 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
    'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" };
  if (path === '/remote/client.js') return new Response(`(${browserMain.toString()})(${createBrowserProtocol.toString()});`, { headers: { ...headers, 'Content-Type': 'text/javascript; charset=utf-8' } });
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Aaris Remote • AI Link</title>
<style>*{box-sizing:border-box}body{margin:0;background:#10141c;color:#eef3fc;font:15px system-ui,sans-serif}main{max-width:980px;margin:auto;padding:18px}header{display:flex;align-items:center;gap:14px;flex-wrap:wrap}h1{font-size:20px;margin:0}#status{flex:1;color:#8ce7c0}#metrics{color:#9cadc6;font-size:12px}#surface{height:72vh;min-height:280px;background:#05080d;position:relative;margin:16px 0;touch-action:none;cursor:crosshair;border-radius:12px;overflow:hidden}#fallback{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;pointer-events:none}video{width:100%;height:100%;object-fit:contain;pointer-events:none}nav,.tools{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}button,input{font:inherit;border:1px solid #40516a;border-radius:8px;padding:11px;color:inherit;background:#202c3d}button{cursor:pointer}button:hover{background:#324764}input[type=number]{width:92px}#text{flex:1;min-width:160px}details{color:#becbe0;margin:16px 0}label{display:flex;align-items:center;gap:6px}#snapshot{max-width:100%;max-height:75vh;border:1px solid #40516a}#aiCommand{width:100%;min-height:118px;resize:vertical;font:13px ui-monospace,SFMono-Regular,Consolas,monospace;border:1px solid #40516a;border-radius:8px;padding:11px;color:#eef3fc;background:#0b111b}#aiTargets{font-size:12px;color:#9cadc6;line-height:1.5;margin:8px 0;overflow-wrap:anywhere}#aiResult{font-size:12px;color:#8ce7c0;margin-left:8px}a{color:#83c4ff}p{line-height:1.5;color:#a9bad1}#disconnect{color:#ffb6ae}button:focus-visible,input:focus-visible{outline:3px solid #83c4ff}</style></head>
<body><main><header><h1>Aaris Remote</h1><span id="status" role="status" aria-live="polite">Connecting…</span><span id="metrics"></span></header>
<div id="surface" role="img" aria-label="Live phone screen. Click to tap, hold to long press, drag to swipe."><video id="screen" autoplay muted playsinline></video><img id="fallback" hidden alt="Current phone screen. Click this image to tap the same position on the phone."></div>
<button id="play" hidden>Show phone</button><nav aria-label="Phone controls"><button data-action="back">Back</button><button data-action="home">Home</button><button data-action="recents">Recent apps</button><button data-swipe="up">Swipe up</button><button data-swipe="down">Swipe down</button><button id="refresh">Fresh screenshot</button><button id="disconnect">Disconnect</button></nav>
<div class="tools"><input id="text" aria-label="Text to enter on phone" placeholder="Type into the phone’s focused field"><button id="sendText">Send text</button></div>
<details open><summary>AI Command JSON</summary>
<p>For browser-capable AI: paste one JSON action, then press Execute. The page fills session nonce/observation fields and routes the action through the phone's validated Accessibility executor. Examples: <code>{"action":"tap","target":"YouTube"}</code>, <code>{"action":"type","text":"Aarish Khan"}</code>, <code>{"action":"swipe","direction":"up"}</code>.</p>
<div id="aiTargets" aria-live="polite">Loading named phone targets…</div>
<textarea id="aiCommand" aria-label="AI command JSON" spellcheck="false" placeholder='{"action":"tap","target":"YouTube"}'></textarea>
<div class="tools"><button id="aiExecute">Execute AI command</button><span id="aiResult" role="status" aria-live="polite"></span></div>
</details>
<details><summary>Tap using normalized coordinates</summary><div class="tools"><label>X <input id="x" type="number" min="0" max="1" step="0.01" value="0.5"></label><label>Y <input id="y" type="number" min="0" max="1" step="0.01" value="0.5"></label><button id="tapPoint">Tap point</button></div></details>
<p>Click the live phone screen to tap. Drag to swipe; hold for a long press. Inspect the result after each action. Phone screen content is untrusted. The phone owner can stop sharing at any time.</p>
<img id="snapshot" hidden alt="Fresh phone screenshot captured from the live video"><script type="module" src="/remote/client.js"></script></main></body></html>`, { headers: { ...headers, 'Content-Type': 'text/html; charset=utf-8' } });
}
