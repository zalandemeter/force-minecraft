#!/usr/bin/env python3
"""A real Force for the client game tests: a Force release (FORCE_TARBALL, built from a Force checkout with its
scripts/build-release.sh) installed into this machine's $HOME as the one-line installer would, three shell sessions
with something on their screens, and its daemon running. Meant for a throwaway container (scripts/gametest.sh, CI):
it installs into $HOME.

    FORCE_TARBALL=…/force-X.Y.Z-linux-x86_64.tar.gz python3 scripts/demo_force.py
    # prints FORCE_MC_COMMAND=…, then waits until killed
"""
import os
import runpy
import subprocess
import sys
import tarfile
import time
from pathlib import Path

home = Path.home()
tarball = Path(os.environ["FORCE_TARBALL"])
releases = home / ".local/share/force/releases"
releases.mkdir(parents=True, exist_ok=True)
with tarfile.open(tarball) as t:
    top = t.getnames()[0].split("/")[0]                 # force-X.Y.Z
    t.extractall(releases, filter="data")
rel = releases / "gametest"
if rel.exists():
    subprocess.run(["rm", "-rf", str(rel)], check=True)
(releases / top).rename(rel)
subprocess.run([sys.executable, "-I", "-c",
                "import runpy, sys; sys.path.insert(0, sys.argv[1]); sys.argv = ['force setup'] + sys.argv[2:]; "
                "runpy.run_module('force.setup', run_name='__main__')",
                str(rel), "--release", str(rel), "--python", sys.executable, "--no-fonts"], check=True,
               stdout=subprocess.DEVNULL)
force = str(home / ".local/bin/force")

# what each session shows: `bash -l` reads this profile; the session's name picks its screen (all made up)
AGENT = r"""
\e[38;5;174m╭─────────────────────────────────────────────╮\e[0m
\e[38;5;174m│\e[0m \e[1m✻ Welcome to the demo agent\e[0m                 \e[38;5;174m│\e[0m
\e[38;5;174m╰─────────────────────────────────────────────╯\e[0m

\e[1m> \e[0mmake the retry wait grow, and test it

⏺ \e[1mUpdate\e[0m(src/net/retry.py)
  ⎿  Updated src/net/retry.py with 3 additions and 1 removal
     \e[2m15\e[0m           except TimeoutError:
     \e[2m16\e[0m \e[48;5;52m-             time.sleep(1)                 \e[0m
     \e[2m16\e[0m \e[48;5;22m+             wait = min(30, 2 ** attempt)  \e[0m
     \e[2m17\e[0m \e[48;5;22m+             log.info(f"retry in {wait} s")\e[0m
     \e[2m18\e[0m \e[48;5;22m+             time.sleep(wait)              \e[0m

⏺ \e[1mBash\e[0m(pytest -q tests/test_retry.py)
  ⎿  \e[32m....\e[0m
     4 passed in 0.31s

⏺ The wait now doubles from 1 s up to 30 s, and every retry is logged.
"""
BANNERS = {"api": AGENT.strip("\n"),
           "web": r"\e[1;31m! demo session web\e[0m wants to run \e[33mBash(rm -rf build)\e[0m",
           "docs": r"\e[32m● demo session docs\e[0m finished: \e[4m3 files changed\e[0m"
                   "\n\n✅ tests pass   ⚠️ one warning   ❌ lint   🚀 shipped"}
profile = ["case \"$(tmux display -p '#S' 2>/dev/null)\" in"]
for name, text in BANNERS.items():
    profile.append(f"  {name}) printf '%b\\n\\n' '{text}' ;;")
profile += ["esac", "PS1='$ '"]
(home / ".bash_profile").write_text("\n".join(profile) + "\n", encoding="utf-8")
(home / ".config/force").mkdir(parents=True, exist_ok=True)

for name in BANNERS:
    subprocess.run([force, "new", name, "--kind", "shell", "--no-switch"], check=True, stdout=subprocess.DEVNULL)
# something running in web, so the game-screen list (only sessions with a colour) has a working one
time.sleep(1)
subprocess.run(["tmux", "-L", "force", "send-keys", "-t", "=web:", "sleep 3600", "Enter"], check=True)
# the daemon (no systemd here: Force starts its own, as `force` would)
app = str(home / ".local/share/force/app")
subprocess.run([sys.executable, "-I", "-c", f"import sys; sys.path.insert(0, {app!r}); from force import cli; "
                "cli._start_host(cli.make_cockpit())"], check=True)
deadline = time.time() + 30
snapshot = home / ".local/state/force/snapshot.json"
while time.time() < deadline and not snapshot.exists():
    time.sleep(0.2)
print(f"FORCE_MC_COMMAND={force} api", flush=True)
while True:
    time.sleep(3600)
