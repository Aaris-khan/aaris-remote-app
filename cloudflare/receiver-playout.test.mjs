import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdaptivePlayoutPolicy } from './receiver-playout.js';

const sample = (policy, nowMs, overrides = {}) => ({
  nowMs, intervalMs: 1000, frames: 30, dropped: 0, freezes: 0, decodeMs: 4,
  minimumDelayMs: 30, actualDelayMs: policy.targetMs(), targetDelayMs: policy.targetMs(),
  acceptedTargetMs: policy.targetMs(), ...overrides
});

test('subtract only accepted application delay, never hide an inflated native target', () => {
  const p = createAdaptivePlayoutPolicy();
  assert.equal(p.pressureDelayMs(270, 250, 250), 20);
  assert.equal(p.pressureDelayMs(720, 700, 250), 470);
  assert.equal(p.pressureDelayMs(180, 400, 100), 300, 'rising target is early pressure');
  assert.equal(p.pressureDelayMs(180, 80, 250), 100, 'unapplied requested delay cannot erase queueing');
  assert.equal(p.pressureDelayMs(180, 0, 250), 180);
  assert.equal(p.pressureDelayMs(270, 250, null), 270, 'unsupported API has no intentional allowance');
  assert.equal(p.pressureDelayMs(null, null, 100), null);
});

test('jitter evidence raises one rung at a time with cooldown and a bounded ceiling', () => {
  const p = createAdaptivePlayoutPolicy();
  assert.equal(p.targetMs(), 100);
  const jitter = now => p.observe(sample(p, now, { minimumDelayMs: p.targetMs() - 10 }));
  assert.equal(jitter(1000).changed, false);
  assert.equal(jitter(2000).targetMs, 150);
  assert.equal(jitter(3000).changed, false);
  assert.equal(jitter(4000).changed, false, 'cooldown prevents buffer oscillation');
  for (let t = 5000; t <= 25000; t += 1000) jitter(t);
  assert.equal(p.targetMs(), 500);
});

test('decoder overload, excess queue, idle screens, losses alone and unknown stats never buy latency', () => {
  for (const overrides of [
    { decodeMs: 70, minimumDelayMs: 95, freezes: 1 },
    { actualDelayMs: 650, targetDelayMs: 600, minimumDelayMs: 95, freezes: 1 },
    { frames: 1, minimumDelayMs: 95, freezes: 1 },
    { dropped: 10, freezes: 1 },
    { decodeMs: null, minimumDelayMs: 95 },
    { minimumDelayMs: null, freezes: 1 },
    { acceptedTargetMs: null, minimumDelayMs: 95, freezes: 1 }
  ]) {
    const p = createAdaptivePlayoutPolicy();
    for (let t = 1000; t <= 15000; t += 1000) p.observe(sample(p, t, overrides));
    assert.equal(p.targetMs(), 100, JSON.stringify(overrides));
  }
});

test('recovery requires 30 elapsed clean seconds; replays and gaps cannot fake it', () => {
  const p = createAdaptivePlayoutPolicy();
  p.observe(sample(p, 1000, { minimumDelayMs: 95, freezes: 1 }));
  assert.equal(p.targetMs(), 150);
  for (let i = 0; i < 100; i++) p.observe(sample(p, 2000));
  assert.equal(p.targetMs(), 150, 'duplicate callbacks are not clean time');
  p.observe(sample(p, 50000));
  assert.equal(p.targetMs(), 150, 'background time is not clean time');
  for (let t = 51000; t <= 79000; t += 1000) p.observe(sample(p, t));
  assert.equal(p.targetMs(), 150);
  assert.equal(p.observe(sample(p, 80000)).targetMs, 100);
});

test('target transition reports pressure from the completed interval, not the new larger budget', () => {
  const p = createAdaptivePlayoutPolicy();
  const state = p.observe(sample(p, 1000, { minimumDelayMs: 95, actualDelayMs: 145, freezes: 1 }));
  assert.equal(state.targetMs, 150);
  assert.equal(state.excessDelayMs, 45);
});

test('planned downshift gets a bounded drain allowance without hiding newly growing backlog', () => {
  const p = createAdaptivePlayoutPolicy();
  p.observe(sample(p, 1000, { minimumDelayMs: 95, freezes: 1 }));
  for (let t = 2000; t <= 32000; t += 1000) p.observe(sample(p, t));
  assert.equal(p.targetMs(), 100);
  const draining = { actualDelayMs: 150, targetDelayMs: 150 };
  assert.equal(p.observe(sample(p, 33000, draining)).excessDelayMs, 0);
  assert.equal(p.observe(sample(p, 33500, { actualDelayMs: 400, targetDelayMs: 400 })).excessDelayMs, 250);
  assert.equal(p.observe(sample(p, 34500, draining)).excessDelayMs, 50, 'old allowance expires after two seconds');
});

const before = {
  id: 'video', timestamp: 1000, framesDecoded: 100, jitterBufferEmittedCount: 100,
  totalDecodeTime: .4, jitterBufferDelay: 10, jitterBufferTargetDelay: 10,
  jitterBufferMinimumDelay: 3, framesDropped: 2, freezeCount: 1
};
const after = {
  ...before, timestamp: 2000, framesDecoded: 130, jitterBufferEmittedCount: 130,
  totalDecodeTime: .52, jitterBufferDelay: 13.6, jitterBufferTargetDelay: 13,
  jitterBufferMinimumDelay: 3.9
};
test('stats use interval deltas, reject counter resets and preserve unavailable measurements', () => {
  const p = createAdaptivePlayoutPolicy();
  const s = p.sampleStats(after, before);
  assert.equal(s.frames, 30);
  assert.ok(Math.abs(s.decodeMs - 4) < .0001);
  assert.ok(Math.abs(s.actualDelayMs - 120) < .0001);
  assert.ok(Math.abs(s.minimumDelayMs - 30) < .0001);
  for (const patch of [{ id: 'replacement' }, { codecId: 'replacement-codec' }, { timestamp: 1000 }, { timestamp: 10000 },
    { framesDecoded: 0 }, { jitterBufferEmittedCount: 0 }, { freezeCount: 0 },
    { totalDecodeTime: 0 }, { jitterBufferDelay: 0 }]) {
    assert.equal(p.sampleStats({ ...after, ...patch }, before), null);
  }
  const sparse = p.sampleStats({ ...after, totalDecodeTime: undefined, framesDropped: undefined,
    freezeCount: undefined, jitterBufferMinimumDelay: undefined, jitter: .05 }, before);
  assert.equal(sparse.decodeMs, null);
  assert.equal(sparse.dropped, null);
  assert.equal(sparse.freezes, null);
  assert.equal(sparse.minimumDelayMs, null, 'interarrival jitter is not playout delay');
  const noFrames = p.sampleStats({ ...before, timestamp: 2000 }, before);
  assert.equal(noFrames.decodeMs, null);
  assert.equal(noFrames.actualDelayMs, null);
});

test('presentation cadence counts compositor frames without double-counting or hiding gaps', () => {
  const p = createAdaptivePlayoutPolicy();
  assert.equal(p.present(1000, 1), null);
  for (let i = 1; i < 15; i++) assert.equal(p.present(1000 + i * 1000 / 30, i + 1), null);
  assert.deepEqual(p.present(1500, 16), { intervalMs: 500, frames: 15, maxGapMs: 33 });
  assert.equal(p.present(1510, 16), null);
  assert.equal(p.present(1534, 17), null);
  assert.deepEqual(p.present(2000, 18), { intervalMs: 500, frames: 2, maxGapMs: 466 });
  assert.equal(p.present(2500, 33), null, 'missed callbacks cannot masquerade as a video freeze');
  p.resetPresentation();
  assert.equal(p.present(10000, 100), null);
  assert.equal(p.present(9000, 90), null);
  assert.equal(p.present(9100, 91), null);
});
