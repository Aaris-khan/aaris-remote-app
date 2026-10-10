# Optional semantic click and animated-screen control

Version 1.9.62 keeps ordinary `phone_action(action="tap")` on the existing
80 ms touch-gesture path. There is no automatic second click and no file-picker
package allowlist. `semantic_click` is an explicit optional alternative for any
bounded app or website control that exposes Android Accessibility `ACTION_CLICK`.
The APK and MCP Worker must both be upgraded. Older APKs remain able to use tap;
the upgraded Worker refuses unsupported semantic input before action admission.

## Agent decision flow

| Situation | Next action |
| --- | --- |
| Fresh screenshot, ordinary interaction | Use `tap` with screenshot coordinates. |
| Ordinary taps completed but the intended control repeatedly has no visible effect | Inspect the returned screenshot, allow any pending operation to finish, then optionally choose `semantic_click` with the latest observation and a new action ID. |
| Transport outcome is unknown | Retry the same ID with identical arguments to retrieve its recorded outcome; observe before deciding another method. |
| Native click is unavailable or refuses input | Inspect its fresh screenshot and choose a different strategy; the phone never injects a hidden touch fallback. |
| File row appears selected but picker remains open | Check whether Open, Select, Done, a checkbox, or multi-selection confirmation is still required. |
| Picker closes | Verify the website's attachment and upload result separately. `applied=true` confirms input, not upload. |
| Video, clocks, streaming text, or another region animates | A stable bounded control keeps its ticket. Use `phone_wait` on the relevant region when waiting for an outcome. |
| A popup blocks the control or its app/window changes | Use the returned screenshot to deliberately dismiss/handle it and then resume the stored task. No blind click-through. |

Example optional request after confirmed ordinary no-effect taps:

```json
{
  "actionId": "resume-native-0001",
  "observationId": "LATEST_OBSERVATION_ID",
  "screenVersion": 12,
  "action": "semantic_click",
  "x": 0.42,
  "y": 0.58,
  "responseQuality": "detail",
  "intent": "Activate the visible resume PDF row",
  "expectedOutcome": "Selection or picker confirmation appears"
}
```

## On-device mapping

| Stage | Ordinary `tap` | Optional `semantic_click` |
| --- | --- | --- |
| Intent source | Fresh full-screen screenshot | Same screenshot; no Accessibility tree is published |
| Coordinate resolution | Existing bounded auto-aim, otherwise raw coordinate | Smallest bounded node advertising ACTION_CLICK at the coordinate, including a filename label's clickable parent |
| Identity guard | App/window, geometry, privacy, actual bounded control, local pixels | App/window, geometry, privacy, tree path, bounds, role/id, full-text digests, descendant labels, supported action |
| Animated pixels | Padded guard clipped to the actual control; a stable visual target/corridor can also survive large background video when useful nodes are absent | Fresh semantic identity authorizes the native action despite changing pixels |
| Execution | Existing `dispatchGesture` and gesture callback | Reacquire live root and node, refresh, recheck authorization/precondition, exactly one ACTION_CLICK |
| Failure | Fresh screenshot and effect metadata | Fresh screenshot and no silent retry/SELECT/FOCUS/touch fallback |
| Outcome | `inputMethod: touch_gesture` | `inputMethod: accessibility_click` |

Repeated file-row resource IDs are resolved by coordinate, tree path and row
labels, never by the first resource ID match. Full-text hashes distinguish long
filenames whose first 160 visible snapshot characters are identical. Semantics,
paths and hashes remain local. STOP, expired leases, lock/privacy and actual
window/target changes still revoke pending input. Existing task memory, regional
waits, gestures and one-shot benign reflexes remain available; semantic clicks
cannot be put in bursts or remembered as reflexes.

`responseQuality=detail` now applies to action-result screenshots as well as
observations. Effect and stall hints distinguish background motion from target
progress and explain when the optional method or picker confirmation may help.

## Verification boundary

Regression coverage includes repeated PDF row IDs, recycled filenames, long
filenames, disabled/moved targets, capability mismatch, uncertain retries,
native no-effect results, normal tap preservation and a tiny search button next
to changing video. Android unit tests/lint/build and the MCP tests verify these
paths; an actual picker-to-website upload still needs the affected phone/browser
because the destination app owns the file URI callback and network upload.
