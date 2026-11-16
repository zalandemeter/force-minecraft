#!/bin/sh
# Run the client game tests in a container: the real Minecraft client on a virtual display, against a demo Force
# built from a Force checkout (FORCE_REPO, default ../force). Screenshots: run/clientGameTest/screenshots/.
#   scripts/gametest.sh [GRADLE ARGS…]   e.g. -Pminecraft_version=26.3 -Pfabric_api_version=0.166.2+26.3
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
force_repo=$(cd "${FORCE_REPO:-$here/../force}" && pwd)
gradle_home=${GRADLE_USER_HOME:-$HOME/.gradle}
image=force-mc-gametest
docker build -q -t "$image" "$here/scripts/gametest" >/dev/null
mkdir -p "$gradle_home"
exec docker run --rm --init --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle -e FORCE_REPO=/force -e LIBGL_ALWAYS_SOFTWARE=1 \
  -v "$here:/work" -v "$force_repo:/force:ro" -v "$gradle_home:/gradle" -w /work "$image" sh -c '
    mkdir -p /tmp/home
    Xvfb :99 -screen 0 1600x900x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
    export DISPLAY=:99
    python3 scripts/demo_force.py /tmp/force-demo >/tmp/demo.log 2>&1 &
    for i in $(seq 50); do grep -q FORCE_MC_COMMAND /tmp/demo.log 2>/dev/null && break; sleep 0.2; done
    export FORCE_MC_COMMAND="$(sed -n "s/^FORCE_MC_COMMAND=//p" /tmp/demo.log)"
    ./gradlew runClientGameTest --no-daemon --console=plain "$@"
  ' gametest "$@"
