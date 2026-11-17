# Contributing

How the mod is built, tested and released, and how its code is laid out. It is a frontend of
[Force](https://github.com/zalandemeter/force): it talks to an installed Force through `force api` (Force's
`docs/SPEC.md` §54) and keeps no state of its own about sessions.

## How a change gets in

Work on a `<type>/<slug>` branch (`feat/…`, `fix/…`, `docs/…`) and open a pull request with a
[Conventional Commits](https://www.conventionalcommits.org) title: `feat: a settings page`. The title is what the
release notes list, so write it for players. CI (`build`) builds the mod for both Minecraft versions, runs the unit
tests, and runs the client game tests against a Force built from Force's `main`. The maintainer merges.

## Develop

```sh
./gradlew build                 # the mod and the unit tests (Java 25), for Minecraft 26.2
./gradlew build -Pminecraft_version=26.3 -Pfabric_api_version=0.166.2+26.3   # for Minecraft 26.3
scripts/gametest.sh             # the client game tests in Docker, against a demo Force from ../force
./gradlew runClient             # a dev client; FORCE_MC_COMMAND="/path/to/force api" points it at another Force
```

`scripts/gametest.sh` needs Docker and a Force checkout (`FORCE_REPO`, default `../force`): it builds a Force
release from it, installs it in the container with three shell sessions and its daemon, and runs the client against
it (gradle arguments pass through, e.g. the 26.3 ones above). Screenshots of each step land in
`build/run/clientGameTest/screenshots/`; the README's pictures come from there. The container has no emoji font, so
the bundled one is tested.

`scripts/icon.py` draws the icon, `scripts/icons.py` cuts Force's icons out of Symbols Nerd Font Mono, and
`scripts/widths.py` generates the character widths tmux uses.

## Versions

The mod follows the newest release of [Fabulously Optimized](https://modrinth.com/modpack/fabulously-optimized),
and builds for the next Minecraft too:

| | default build | also |
|---|---|---|
| Fabulously Optimized | 14.1.2 | 15 (beta) |
| Minecraft | 26.2 | 26.3 |
| Fabric API | 0.161.0+26.2 | 0.166.2+26.3 |
| Fabric Loader | 0.20.1 | 0.20.1 |
| Java | 25 | 25 |

Minecraft 26.3 moved its input from GLFW to SDL (other key codes and modifier bits), so keys go through `KeyTable`,
built from Minecraft's own `InputConstants`, never through literal codes.

## Releases

A release is a tag: `git tag vX.Y.Z && git push origin vX.Y.Z` on a commit of `main`, numbered as Force's are
(plain X.Y.Z). The tag is the version; nothing in the repository holds it (a development build is 0.0.0).
`.github/workflows/release.yml` checks the commit (on `main`, newer than every release, its CI passed), builds one jar
per Minecraft version (`force-minecraft-X.Y.Z+26.2.jar`, `…+26.3.jar`) with their SHA-256, and publishes a GitHub
Release whose notes list the merged pull requests. When the repository has a `MODRINTH_TOKEN` secret and a
`MODRINTH_PROJECT_ID` variable, it uploads both jars to Modrinth too, as versions of the same number.

## Architecture

Built so the UI, the gestures and the terminal technology can change one at a time.

```
src/main/java/cc/useforce/mc/core/      plain Java, no Minecraft (unit-tested)
  ApiConnection, Supervisor             `force api` over stdio: requests, events, restart with backoff
  ForceModel                            what Force said: hosts, sessions, attention, notices (Force decides)
  Selection                             which session is picked: the UI's state, never Force's
  AppTerminal, Vt, VtInput              the whole app (vt mode): Force runs its ui in a pty, Vt emulates the
                                        terminal (what tmux sends), VtInput encodes keys, pastes and the mouse
  TerminalBackend, CellsBackend         one session's view (cells mode): the native session view
  TerminalBuffer                        the screen both fill: the only thing the view draws
  Intent, Controller                    what the player wants, from any gesture; carried out in one place
  Keys, Widths                          key names as tmux knows them; character widths as tmux counts them
src/client/java/cc/useforce/mc/         Minecraft
  ui/Theme, Panel                       Force's colours and the glass panels
  ui/SessionList, ui/TerminalView       widgets: draw a model / a buffer, answer "what is here"
  ui/GlyphAtlas, ui/BoxDraw             the text renderer (below), box lines
  Hud, AppScreen, ConsoleScreen         layouts: the game screen; the whole app; the session view
  Config, SettingsScreen, ModMenuEntry  the settings: the file, the screen (Minecraft's option widgets), Mod Menu
  ComboKey, mixin/KeyBindsScreenMixin   key bindings that can be combinations, set in Controls → Key Binds
  ForceClient                           wiring: key bindings, toasts, the layouts, the controller
src/gametest/                           the real client, headless: a world, the app, typing, switching, emoji,
                                        the settings, a key combination
```

- A new gesture (a click, a key, the wheel) maps to an existing `Intent`; a new behaviour is a new `Intent`.
- Moving the list (into the HUD, the console, or a side panel) is a layout change; the widgets do not care.
- The app view and the session view fill the same `TerminalBuffer`, so the renderer is shared.

## Text

The mod draws its own text (`ui/GlyphAtlas`): FreeType rasterises each glyph at exactly the pixel size it is drawn,
into texture pages, and everything is drawn in screen pixels rather than Minecraft's scaled GUI units, so text is
crisp at any GUI scale and sizes follow the GUI scale. It does not go through Minecraft's font system, which loads
TTF fonts on several threads at once (FreeType's TrueType hinting interpreter crashes under that with many sizes);
one FreeType library of the mod's own is used by the render thread only, with light auto-hinting.

Fonts, in order: JetBrains Mono (with its real bold), Force's icons (Symbols Nerd Font Mono, cut down), DejaVu Sans
Mono and DejaVu Sans. Emoji come in colour from the system's emoji font, else the bundled Noto Color Emoji: those no
text font has, those marked with VS16 (⚠️), and the pictographs from U+1F000 on. Box-drawing and block characters
are drawn as lines and blocks that meet across cells. Backgrounds are drawn before any text, so a glyph a little
wider than its cell runs over its neighbours as in a terminal.
