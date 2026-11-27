# M3 gate evidence

Gate (prompts/m3.md): `samples/hud-box` passes the automated L4 gate on the core six (tick counts, one join, pixel
probes, no Mixin error) and the registered key, pressed by the testkit, toggles the rectangle; run twice in a row on
all six.

- Code: `commits.txt` (public 0325d87, blanket-emit c1dc022).
- Machine: testbox container 155, Debian 13, 4 cores of an i5-7500, 6 GB, no GPU, Xvfb 854x480x24, Mesa llvmpipe.
- Script: `gate-m3.sh` (copy of `S:/blanket-wt/gate-m3.sh`): fresh container, `tools/linux-setup.sh`,
  `bash gradlew build :blanket-emit:emitHudBox :blanket-testkit:installDist` (`build.log.gz`: BUILD SUCCESSFUL,
  validateBindings 0 errors 2 warnings, `checkHudBoxJars` 6 jars/423 classes), then per round and target
  `xvfb-run blanket-testkit run <target> --home <spike> --jar hudbox-0.0.0+<mc>-<loader>.jar
  --probes samples/hud-box/testkit-probes.json --out <dir> --timeout 360`.
- Per run folder `r<round>/<target>/`: `testkit-result.json` (written by the in-game testkit), `verdict.txt` (the
  harness), `command.txt` (the game command line), `game.log.gz`. Jar hashes: `jars.sha256`.

| Target | Round | Ticks start = end | Joins | HUD before key | Box | HUD after key | Verdict |
|---|---|---|---|---|---|---|---|
| legacy-fabric:1.8.9 | 1 | 125 | 1 | `#FF00FF` | `#00FD00` | `#83AAF1` | PASS |
| forge:1.8.9 | 1 | 115 | 1 | `#FF00FF` | `#00FD00` | `#83AAF1` | PASS |
| fabric:1.21.1 | 1 | 181 | 1 | `#FF00FF` | `#00FA00` | `#7496D4` | PASS |
| neoforge:1.21.1 | 1 | 136 | 1 | `#FF00FF` | `#00FA00` | `#7496D4` | PASS |
| forge:1.20.1 | 1 | 140 | 1 | `#FF00FF` | `#00FA00` | `#7395D3` | PASS |
| fabric:26.1.2 | 1 | 231 | 1 | `#FF00FF` | `#00FB00` | `#7B9DDC` | PASS |
| legacy-fabric:1.8.9 | 2 | 127 | 1 | `#FF00FF` | `#00FE00` | `#84ABF2` | PASS |
| forge:1.8.9 | 2 | 117 | 1 | `#FF00FF` | `#00FE00` | `#84ABF2` | PASS |
| fabric:1.21.1 | 2 | 188 | 1 | `#FF00FF` | `#00FA00` | `#7496D4` | PASS |
| neoforge:1.21.1 | 2 | 149 | 1 | `#FF00FF` | `#00FA00` | `#7496D4` | PASS |
| forge:1.20.1 | 2 | 143 | 1 | `#FF00FF` | `#00FA00` | `#7395D3` | PASS |
| fabric:26.1.2 | 2 | 225 | 1 | `#FF00FF` | `#00FB00` | `#7A9CDB` | PASS |

Every run: 0 listener failures, no Mixin error line, no crash marker. The HUD probe is read at framebuffer (60, 60)
(GUI (30, 30) at GUI scale 2); "HUD after key" is the same pixel 10 ticks after the testkit pressed the registered
key: sky, because `[hudbox] toggle visible=false` hid the rectangle.

Not in this run: the key hand-over log line (`[blanket] hudbox: handed 1 key mapping(s) to the game`) was added after
it (bca2a8e); the verification run shows it. The harness screenshot is taken after the game has quit and is black, so
it is not kept.
