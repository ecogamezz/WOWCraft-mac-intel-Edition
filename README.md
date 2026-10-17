# WowMacCraft

Minecraft played inside World of Warcraft 1.12, on macOS (Intel). A port of
[WowCraft](https://github.com/over825hp/wowcraft), which is Windows-only: the same launcher, the
same server and database, the same Minecraft mod and the same WoW client module, built and
running natively on a Mac.

You walk, jump, swim and fly with Minecraft's physics in WoW's world; Minecraft's hearts are your
life; you fight WoW's creatures with Minecraft's weapons; you mine WoW's ore veins and pick its
herbs as Minecraft blocks; quests and kills reward Minecraft loot; Minecraft's hotbar, inventory
and chat draw over WoW.

## How it fits together

| Part | WowCraft (Windows) | WowMacCraft (this repo) |
| --- | --- | --- |
| Launcher | Rust + egui `WowCraft.exe` | `launcher/`: the same launcher, ported (processes via libproc, POSIX signals, `.app` bundle) |
| Server | vmangos `mangosd`/`realmd` + MariaDB for Windows | vmangos and MariaDB built from source for macOS (`scripts/build-deps.sh`) |
| WoW client | Benilla with a closed `wowcraft` module | Benilla at a pinned commit + `client/patches`: the module rewritten in full |
| Minecraft | Prism Launcher, Minecraft 26.3 Fabric, the WowCraft mod | Prism Launcher for macOS; the same mod, its link patched for macOS (`mod/`) |
| Link | Windows named shared memory, a named mutex, `QueryPerformanceCounter` | POSIX shared memory `/SkyCraft_v1.<uid>`, a lock file, `CLOCK_UPTIME_RAW` |

The link is the SkyCraft shared-memory protocol (version 11) with WowCraft's extensions: game and
Minecraft state, input, the actor table, events, world entities, collision, the overlay's
triple buffer and the render ring. Both sides lay it out byte for byte as WowCraft does.

## Building

On an Intel Mac with macOS 12 or newer, Xcode Command Line Tools, Rust, git and Homebrew's
`openssl@3`:

```bash
scripts/build-deps.sh
```

builds CMake and the JDK (downloaded), MariaDB 10.11.19, vmangos and Benilla with the WowCraft
module into `build/`. Then

```bash
scripts/package.sh
```

fetches WowCraft v0.1.1 (for its database, configs and Minecraft instance) and Prism Launcher
11.1.1 for macOS, builds the launcher, patches the mod for macOS and assembles
`dist/WowMacCraft/` and `dist/WowMacCraft.zip`. `scripts/package.sh --no-zip` skips the zip.
A rebuild keeps a played `dist/WowMacCraft`'s state (the launcher's settings, the database, the
server's maps and logs, the bundled Prism's sign-in and instance with its worlds, the WoW client's
`benilla-config`) and only replaces the package around it; the zip never carries that state. Quit
WowMacCraft before rebuilding: the script stops while anything runs from that folder.

If the C++ build of MariaDB or vmangos fails on `<cstddef>` or similar, an old Command Line Tools
install has left stale headers in `/Library/Developer/CommandLineTools/usr/include/c++/v1`; the
scripts work round it, and `sudo rm -rf` of that folder fixes it for good.

## Playing

See `packaging/README.txt` (the package's own README): unzip `WowMacCraft.zip`, open
`WowMacCraft.app`, choose your WoW 1.12.1 folder in Settings and press PLAY. A Windows WoW 1.12.1
client works too: only its `Data` files are read.

## The parts

- `launcher/`: the WowMacCraft launcher (`cargo build --release`, `cargo test`).
- `mod/`: the macOS patch for the WowCraft mod jar (`mod/build.sh <jar>`) and stand-in Minecraft
  test harnesses that talk to the game through the patched mod's own link code.
- `client/`: the Benilla commit and the patch series with the WowCraft client module.
- `scripts/`: `build-deps.sh`, `package.sh`.
- `packaging/`: the package's README and notices.

## Licence

MIT (`LICENSE`), as WowCraft. The bundled components keep their own licences (`licenses/` in
the package): vmangos and MariaDB GPL-2.0, Prism Launcher GPL-3.0, Benilla MIT or Apache-2.0
(the patches in `client/` are under the same terms). World of Warcraft and Minecraft are not
included; you need your own.
