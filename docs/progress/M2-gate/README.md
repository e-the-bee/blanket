# M2 gate evidence, 2026-11-27
Run on testbox container 143 (Debian 13, Mesa llvmpipe, no GPU, Xvfb 854x480x24). Checked by an AI agent; the owner has not looked yet.
Scene: samples/api-tour joins a live local offline server, runs a scripted scene and logs one `[apitour]` block of 26 keys; `tools/check_apitour.py` compares the last block with `expected.json`.
Files per target: `<dir>.png`, `<dir>-f3.png` (scene, F3 open), `<dir>-m0.png` (M0 regression), `<dir>-check.txt` (26 PASS lines), `<dir>.log` (results, last logged block, M0 result).
Also `javap-summary.txt` and `javap-apitour-*.txt`: the off-hand constant is absent from both 1.8.9 jars.
Commands, in `blanket-emit/m0-spike`: `xvfb-run -a -s "-screen 0 854x480x24" python3 tools/run.py <target> --smoke 300 --world --scene apitour --jar ../build/api-tour-jars/apitour-0.0.0+<mc>-<loader>.jar`, then `python3 tools/check_apitour.py run/<dir>/m0-launch.log <target> ../../samples/api-tour/expected.json`.
Commits: scene public d016941 / blanket-emit 804891c; 1.8.9 M0 regression public 36df7c5 / blanket-emit 44d0833 (container 145).
The full launch logs are not kept (large, local paths).
sha256 of the jars (api-tour, then M0):
- 513cdcc887eb2462588f442d8c12c929e077315f0245906e14e847017243d261  apitour-0.0.0+1.20.1-forge.jar
- e66f23ada4615e56f4120bb6bcdf056625abde212915c906b9f7b5589075f9d0  apitour-0.0.0+1.21.1-fabric.jar
- 337f4dc75236e620c39c5745774131dfe1234d3a3c9843bc47ecbf8713d6d948  apitour-0.0.0+1.21.1-neoforge.jar
- 820e57281f1fb5161ee98d1a8a230f6edb2bb9f9542ec8a04964c0119baefcb1  apitour-0.0.0+1.8.9-forge.jar
- a275bfcfbea5ecf5ad04c8db392b6a9f0e330dee512f10a4391489ad078d2f62  apitour-0.0.0+1.8.9-legacyfabric.jar
- dbb2b547e479d8e9c53daddc7121d1662c63b3ab3655afe832c100701730542d  apitour-0.0.0+26.1.2-fabric.jar
- ae41cd052ad6c60b3c48e8bc35e30243b5b66943c70d70b9a78fdc1e5b19cd33  m0spike-0.0.0+1.20.1-forge.jar
- 4fcdd8d596dcb679bf14f4a03835b26e5b0aab862efb7901c575989e6b340949  m0spike-0.0.0+1.21.1-fabric.jar
- 68ac2bd0add07e7df65c156b7eee29fcc3d3d7054f89ca38683822efb2959700  m0spike-0.0.0+1.21.1-neoforge.jar
- d2ea36bb089951a49cf4be3b569fc86954700d80ccc269f43b5c0f1e0b9103fb  m0spike-0.0.0+1.8.9-forge.jar
- fc1c9089465f13dd9cb1430418c0c6197327923e40c9652375c1092735eb0ea2  m0spike-0.0.0+1.8.9-legacyfabric.jar
- 9a9b5ca3cfbb2747fb1e59f7cb5c0b754e4910f309d76df92519485a4eeb66ac  m0spike-0.0.0+26.1.2-fabric.jar
