#!/usr/bin/env bash
# M3 gate on a testbox container (run from GAMING-COMPUTER in Git Bash):
#   OUT=<results dir> ROUNDS=2 bash S:/blanket-wt/gate-m3.sh <container number> [targets...]
# Makes and sizes the container, copies both repos (committed main, LF) and the spike's .cache, runs
# linux-setup.sh (JDKs, Xvfb, Mesa), builds everything and emits hud-box with --testkit, then runs
# blanket-testkit on every target ROUNDS times in a row (each round in a fresh game folder), and copies
# each run's folder (testkit-result.json, verdict.txt, game.log, server.log, shot.png) to OUT/r<round>/<target>.
# Removes the container at the end unless KEEP=1.
set -uo pipefail
N=$1; shift
TARGETS=${*:-legacy-fabric:1.8.9 forge:1.8.9 fabric:1.21.1 neoforge:1.21.1 forge:1.20.1 fabric:26.1.2}
ROUNDS=${ROUNDS:-2}
R=W:/java-mods/blanket/repo/blanket
H=10.137.0.$N
OUT=${OUT:-S:/blanket-wt/M3-gate-results}
SPIKE='~/blanket/blanket-emit/m0-spike'
mkdir -p "$OUT"
if [ "${REUSE:-0}" = 1 ]; then   # container N already set up by an earlier run with KEEP=1: keep caches and JDKs, replace the code
  ssh $H "cd ~/blanket && find . -mindepth 1 -maxdepth 1 ! -name blanket-emit -exec rm -rf {} + &&
    cd blanket-emit && find . -mindepth 1 -maxdepth 1 ! -name m0-spike -exec rm -rf {} + &&
    cd m0-spike && find . -mindepth 1 -maxdepth 1 ! -name .cache ! -name run -exec rm -rf {} +" || exit 2
else
  ssh testbox sudo testvm new debian "$N" || exit 2
  ssh testbox "sudo pct set $N --memory 6144 --swap 2048 --cores 4 && sudo pct resize $N rootfs 24G" || exit 2
fi
stage=S:/blanket-wt/.gate3-stage; rm -rf "$stage"; mkdir -p "$stage/blanket-emit"
(cd $R && git -c core.autocrlf=false archive --format=tar HEAD) | tar -xf - -C "$stage"
(cd $R/blanket-emit && git -c core.autocrlf=false archive --format=tar HEAD) | tar -xf - -C "$stage/blanket-emit"
echo "public $(git -C $R rev-parse --short HEAD), blanket-emit $(git -C $R/blanket-emit rev-parse --short HEAD)" | tee "$OUT/commits.txt"
tar -C "$stage" -cf - . | ssh $H 'mkdir -p ~/blanket && tar -xf - -C ~/blanket' || exit 2
if [ "${REUSE:-0}" != 1 ]; then
  tar -C $R/blanket-emit/m0-spike -cf - .cache | ssh $H "tar -xf - -C $SPIKE" || exit 2
  ssh $H "cd $SPIKE && mkdir -p run && bash tools/linux-setup.sh > ~/setup.log 2>&1; tail -6 ~/setup.log"
fi
TASKS=${TASKS:-build :blanket-emit:emitHudBox :blanket-testkit:installDist}   # a dry run may pass fewer
ssh $H 'cd ~/blanket && export JAVA_HOME=$(ls -d /opt/blanket-jdk/jdk-21* | head -1) BLANKET_JDK8=$(ls -d /opt/blanket-jdk/jdk8* | head -1) &&
  bash gradlew '"$TASKS"' --no-daemon --console=plain > ~/build.log 2>&1; echo "BUILD rc=$?";
  grep -E "BUILD (SUCCESSFUL|FAILED)|FAILED|errors?, [0-9]+ warnings?|jar check|wrote " ~/build.log | head -30;
  ls blanket-emit/build/hud-box-jars'
ssh $H 'cat ~/build.log' > "$OUT/build.log"
ssh $H 'cd ~/blanket/blanket-emit/build && sha256sum hud-box-jars/*.jar' > "$OUT/jars.sha256"
TK='~/blanket/blanket-testkit/build/install/blanket-testkit/bin/blanket-testkit'
for r in $(seq 1 "$ROUNDS"); do
  for t in $TARGETS; do
    mc=${t#*:}; l=${t%%:*}; l=${l//-/}; d=${t/:/-}
    ssh $H "export JAVA_HOME=\$(ls -d /opt/blanket-jdk/jdk-21* | head -1) BLANKET_JDK8=\$(ls -d /opt/blanket-jdk/jdk8* | head -1);
      o=~/gate/r$r/$d; rm -rf \$o; mkdir -p \$o; s=\$(date +%s);
      xvfb-run -a -s '-screen 0 854x480x24' $TK run $t --home $SPIKE --jar ~/blanket/blanket-emit/build/hud-box-jars/hudbox-*+$mc-$l.jar \
        --probes ~/blanket/samples/hud-box/testkit-probes.json --out \$o --timeout 360 > \$o/harness.txt 2>&1;
      echo \"== round $r $t rc=\$? \$(( \$(date +%s) - s ))s\"; tail -3 \$o/verdict.txt 2>/dev/null"
    mkdir -p "$OUT/r$r/$d"
    ssh $H "tar -cf - -C ~/gate/r$r/$d ." | tar -xf - -C "$OUT/r$r/$d"
  done
done
[ "${KEEP:-0}" = 1 ] || ssh testbox sudo testvm rm "$N"
rm -rf "$stage"
