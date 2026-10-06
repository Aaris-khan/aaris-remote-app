# Receiver-aware cadence experiments (1.9.25)

The host previously applied a hard 30 fps session cap, even when source,
encoder and controller could sustain more. Removing that cap alone is unsafe:
decode throughput is not evidence that the viewer submitted those frames to EGL.

The session now starts at at most 30 fps (lower on conservative startup
profiles). During input or passive screen motion it can probe 45 and then 60.
RAM still selects initial pixel geometry; it no longer permanently determines
the motion FPS ceiling. WebRTC keeps ownership of congestion estimation,
bitrate allocation, pacing, retransmission and native quality adaptation.

## Trial rules

| Decision | Evidence / action |
| --- | --- |
| 30 to 45 | Five clean samples spanning at least four seconds |
| 45 to 60 | Eight clean samples spanning at least seven seconds |
| Both promotions | Fresh raw source >=90% of proposed FPS; encode, decode and EGL swaps >=90% of **current** FPS; known compatible display capability |
| Codec admission | Sender and receiver interval-average processing <=70% of proposed frame interval |
| Network admission | Sender average packet send residence <=20 ms; receiver average jitter-buffer residence <=40 ms; RTT <=300 ms; loss <2.5%; limitation reason `none` |
| Render admission | No reported decode drops/freezes; recent presentation age <=1 s; max swap gap <=max(50 ms, 2.2 current frame intervals) |
| Trial validation | After one window of settling, two consecutive windows deliver >=90% of proposed cadence across encode/decode/EGL |
| Failed trial | Immediate rollback on measured pressure, or rollback after two deficient windows / six-second deadline; wait at least 15 s before accumulating another promotion |
| Lost feedback | A proven high-cadence path returns to 30 after >3 s without fresh decode/presentation/capability proof while sampling active video |
| Device pressure | Host severe thermal status limits effective capture to 24; battery saver limits it to 30 |

These values are product-specific starting thresholds, not published Google,
Meta, PUBG or Free Fire constants. Codec values are getStats interval averages,
not p95; pipelined hardware can sustain throughput despite longer residence.
That is why the average gates a trial and real delivery validates it. EGL swap
callbacks measure submission cadence, not physical display scanout or absolute
control-to-photon latency. Supported display modes are a capability bound, not a
guarantee Android will switch modes. The existing Surface frame-rate hint remains
advisory.

`DisplayCapabilities` is an optional, 26-byte, freshness-sensitive packet
alongside the unchanged decoder/presentation packets. It is validated against
the active lease, display generation and increasing sequence, and expires after
three seconds. Older peers ignore its unknown type and keep their existing
feedback; a new host requires the added evidence before promoting above 30.

Pixel recovery is paused during FPS probation so the experiment changes one
resource demand at a time. Existing motion pixel-pressure relief remains
available for the previously observed low-bandwidth/slow-codec failures.
Cadence still escapes an interaction floor under severe queue, decode, render
or CPU pressure. A new touch cannot undo measured overload for eight seconds.
Static screens retain the existing sparse capture/refresh behavior.

## Validation and device acceptance

Regression coverage includes successful 30/45/60 trials, the recorded 22 ms
encoder case staying at 30, decoder-only/stale/missing/invalid feedback, failed
trial rollback and cooldown, severe pressure overriding a touch floor, protocol
bounds, lease/generation isolation and capability expiry. Existing regression
expectations now require presentation evidence for high-cadence recovery.

CI must run Android unit tests, debug/release lint and assembly. CI does not
measure physical-device smoothness. With the same build on both phones, compare
10-minute scroll/video sessions on Wi-Fi and mobile data: delivered EGL FPS,
largest render gap, encoder time, send residence, thermal status and failures.
Exercise congestion followed by recovery, display rotation, a still screen
followed by touch, battery saver, and an older controller build. Record both
screens with a common visible timing event for actual control-to-display delay;
do not subtract unsynchronized phone clocks or call RTT/2 one-way latency.

## Public engineering basis

- [WebRTC Android RtpParameters](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/RtpParameters.java): maximum frame rate/bitrate and degradation preference are application policy controls.
- [Meta: optimizing RTC bandwidth estimation](https://engineering.fb.com/2024/03/20/networking-traffic/optimizing-rtc-bandwidth-estimation-machine-learning/): GCC foundation, network history, and the complexity of overlapping tuning rules.
- [Tencent et al., NSDI 2023](https://www.usenix.org/conference/nsdi23/presentation/meng): adaptive frame rate must account for network fluctuation and receiver decoder capacity.
- [Android PowerManager](https://developer.android.com/reference/android/os/PowerManager): thermal status and battery-saver signals.

No proprietary game networking algorithm is claimed or copied. Local game
rendering/state synchronization and remote screen streaming have different
latency paths; this change targets the measured capture-to-viewer path.
