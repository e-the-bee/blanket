# M1 gate screenshots, 2026-11-26
Run on testbox container 141 (Debian 13, Mesa llvmpipe, no GPU, Xvfb 854x480x24). Checked by an AI agent; the owner has not looked yet.
Files per target (stem = target with `:` replaced by `-`): `<stem>.png` (in-world view), `<stem>-f3.png` (same view, F3 open), `<stem>.log` (run result, box anchor, logged position, F3 XYZ).
Command, in `blanket-emit/m0-spike`: `xvfb-run -a -s "-screen 0 854x480x24" python3 tools/run.py <target> --smoke 300 --world --jar ../build/m0-jars/<jar>`
The jars are the `emitM0` output (built on two machines, byte-identical). sha256:
- ff1744602f0316ed76652254c966c528715b533ee4291bc347c3ec6eaa2c41fe m0spike-0.0.0+1.20.1-forge.jar
- 2fa4d7529e11b9a32d32577534a5493f16f6f992c29b55946501380c4c58de0d m0spike-0.0.0+1.21.1-fabric.jar
- 6edde690af2b0a49cf16cff33f2897a909d6e86ba4b2452e748183b156828128 m0spike-0.0.0+1.21.1-neoforge.jar
- ccad5a93ef71e7c2772f03dc6ba3b3fafcc854f87908163f6417be36d6c207e4 m0spike-0.0.0+1.8.9-forge.jar
- e82a280d767ef3a12625ca102f3c1705a7978df0b0bcdacc81a6b9d4f0dadd53 m0spike-0.0.0+1.8.9-legacyfabric.jar
- 3bce75b19a67a7c4ec4325b775af60fc8e89bb1456606f1f131a30ab7347e03a m0spike-0.0.0+26.1.2-fabric.jar
Reference images from M0 (Windows, NVIDIA GPU): `blanket-emit/m0-spike/gate/*.png` (private repo).
