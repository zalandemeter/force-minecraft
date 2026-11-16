#!/usr/bin/env python3
"""A demo Force for the client game tests: its own state dir and tmux server, three sessions this host and one on a
remote ("devbox", data only), a snapshot kept fresh, and `force` = the real `force api` of a Force checkout.

    FORCE_REPO=~/force python3 scripts/demo_force.py DIR     # prints FORCE_MC_COMMAND=…, then runs until killed
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

repo = os.environ.get("FORCE_REPO", str(Path.home() / "force"))
base = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/force-demo").resolve()
home, state = base / "home", base / "state"
for d in (home, state, base / "claude"):
    d.mkdir(parents=True, exist_ok=True)
(base / "settings.json").write_text(json.dumps({"layout": "classic"}))
sock = "force-mc-demo"
env = {"FORCE_HOME": str(home), "FORCE_STATE": str(state), "FORCE_CLAUDE_DIR": str(base / "claude"),
       "FORCE_CONFIG": str(base / "settings.json"), "FORCE_TMUX_SOCKET": sock, "FORCE_UI_SOCKET": sock + "-ui"}
wrapper = base / "force"
wrapper.write_text("#!/bin/sh\n" + "".join(f"export {k}='{v}'\n" for k, v in env.items())
                   + f"exec python3 -c 'import sys; sys.path.insert(0, \"{repo}\"); from force import cli; "
                     "sys.exit(cli.main(sys.argv[1:]))' \"$@\"\n")
wrapper.chmod(0o755)

# the api session looks like an agent at work: bullets, a diff, a box, tool output (all made up)
AGENT = r"""
\e[38;5;174m╭─────────────────────────────────────────────╮\e[0m
\e[38;5;174m│\e[0m \e[1m✻ Welcome to the demo agent\e[0m                 \e[38;5;174m│\e[0m
\e[38;5;174m╰─────────────────────────────────────────────╯\e[0m

\e[1m> \e[0mmake the retry wait grow, and test it

⏺ Reading the retry loop first.

⏺ \e[1mRead\e[0m(src/net/retry.py)
  ⎿  Read 48 lines

⏺ \e[1mUpdate\e[0m(src/net/retry.py)
  ⎿  Updated src/net/retry.py with 3 additions and 1 removal
     \e[2m12\e[0m       for attempt in range(tries):
     \e[2m13\e[0m           try:
     \e[2m14\e[0m               return call()
     \e[2m15\e[0m           except TimeoutError:
     \e[2m16\e[0m \e[48;5;52m-             time.sleep(1)                 \e[0m
     \e[2m16\e[0m \e[48;5;22m+             wait = min(30, 2 ** attempt)  \e[0m
     \e[2m17\e[0m \e[48;5;22m+             log.info(f"retry in {wait} s")\e[0m
     \e[2m18\e[0m \e[48;5;22m+             time.sleep(wait)              \e[0m

⏺ \e[1mBash\e[0m(pytest -q tests/test_retry.py)
  ⎿  \e[32m....\e[0m
     4 passed in 0.31s

⏺ The wait now doubles from 1 s up to 30 s, and every retry is logged.
  Tests: 4 passed (\e[1mtests/test_retry.py\e[0m).

\e[38;5;244m───────────────────────────────────────────────────────────────────\e[0m
\e[1m>\e[0m 
\e[38;5;244m───────────────────────────────────────────────────────────────────\e[0m
  \e[38;5;179m⏵⏵ auto mode on\e[0m \e[38;5;244m(shift+tab to cycle)\e[0m
"""
banner = {"api": AGENT.strip("\n").replace("\n", "\\n"),
          "web": "\\e[1;31m! demo session web\\e[0m wants to run \\e[33mBash(rm -rf build)\\e[0m",
          "docs": "\\e[32m● demo session docs\\e[0m finished: \\e[4m3 files changed\\e[0m"}
subprocess.run(["tmux", "-L", sock, "kill-server"], capture_output=True)
for name, text in banner.items():
    cmd = f"printf '{text}\\n\\n'; PS1='$ ' exec bash --norc --noprofile -i"
    subprocess.run(["tmux", "-L", sock, "-f", "/dev/null", "new-session", "-d", "-s", name, "-x", "100", "-y", "30",
                    "sh", "-c", cmd], check=True)
    pane = subprocess.run(["tmux", "-L", sock, "display", "-p", "-t", f"={name}:", "#{pane_id}"],
                          capture_output=True, text=True).stdout.strip()
    subprocess.run(["tmux", "-L", sock, "set-option", "-p", "-t", pane, "@force_role", "agent"], check=True)

remote = state / "remotes" / "devbox"
remote.mkdir(parents=True, exist_ok=True)
(base / "hosts.json").write_text(json.dumps({"hosts": [{"name": "devbox", "ssh": "devbox"}]}))
(remote / "status.json").write_text(json.dumps({"state": "connected", "version": "1.6.0"}))


def session(name, i, st, activity, **kw):
    return {"name": name, "index": i, "order": i, "state": st, "cwd": f"/home/demo/{name}", "kind": "claude",
            "activity": activity, "session_id": name, "viewed": False, **kw}


print(f"FORCE_MC_COMMAND={wrapper} api", flush=True)
while True:
    now = int(time.time() * 1000)
    snap = {"generated_ms": now, "sessions": [
        session("api", 1, "WORKING", "editing force/api.py"),
        session("web", 2, "NEEDS_YOU", "Bash: rm -rf build"),
        session("docs", 3, "YOUR_TURN", "finished: 3 files changed", unread=True)]}
    (state / "snapshot.json").write_text(json.dumps(snap))
    (remote / "snapshot.json").write_text(json.dumps({"generated_ms": now, "sessions": [
        session("ci", 1, "IDLE", "watching the release run")]}))
    time.sleep(1)
