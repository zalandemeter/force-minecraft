#!/bin/sh
# Run the client game tests in a container: the real Minecraft client on a virtual display, against a real Force
# (a release built from a Force checkout, installed in the container). Screenshots: build/run/clientGameTest/screenshots/.
#   scripts/gametest.sh [GRADLE ARGS…]   e.g. -Pminecraft_version=26.3 -Pfabric_api_version=0.166.2+26.3
# FORCE_TARBALL: a release tarball to use; else one is built from FORCE_REPO (default ../force).
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
if [ -z "${FORCE_TARBALL:-}" ]; then
  force_repo=$(cd "${FORCE_REPO:-$here/../force}" && pwd)
  out="$force_repo/build/out/mc-gametest"
  FORCE_BUILD_ROOT="$out" FORCE_BUILD_CACHE="$force_repo/build/out/cache" "$force_repo/scripts/build-release.sh" | tail -1
  FORCE_TARBALL=$(ls -t "$out"/dist/releases/force-*-linux-x86_64.tar.gz | head -1)
fi
gradle_home=${GRADLE_USER_HOME:-$HOME/.gradle}
image=force-mc-gametest
docker build -q -t "$image" "$here/scripts/gametest" >/dev/null
mkdir -p "$gradle_home"
exec docker run --rm --init --hostname laptop --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e USER=demo -e LOGNAME=demo -e GRADLE_USER_HOME=/gradle -e LIBGL_ALWAYS_SOFTWARE=1 \
  -e FORCE_TARBALL=/force.tar.gz -v "$FORCE_TARBALL:/force.tar.gz:ro" \
  -v "$here:/work" -v "$gradle_home:/gradle" -w /work "$image" sh -c '
    mkdir -p /tmp/home
    Xvfb :99 -screen 0 2880x1800x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
    export DISPLAY=:99
    python3 scripts/demo_force.py >/tmp/demo.log 2>&1 &
    for i in $(seq 150); do grep -q FORCE_MC_COMMAND /tmp/demo.log 2>/dev/null && break; sleep 0.2; done
    grep -q FORCE_MC_COMMAND /tmp/demo.log || { cat /tmp/demo.log; exit 1; }
    export FORCE_MC_COMMAND="$(sed -n "s/^FORCE_MC_COMMAND=//p" /tmp/demo.log)"
    ./gradlew runClientGameTest --no-daemon --console=plain "$@"
  ' gametest "$@"
