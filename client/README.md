# The WoW client: Benilla with the WowCraft module

WowCraft plays WoW in [Benilla](https://github.com/samwhosung/benilla), an open WoW 1.12.1 client
in Rust and Bevy. WowCraft's own Benilla build carries a closed module (`benilla_app::wowcraft`)
that links it with Minecraft; it ships only as a Windows binary. WowMacCraft rebuilds that module
from what the link protocol and the mod expect, on top of Benilla at the commit in
`BENILLA_COMMIT`, as the patch series in `patches/`.

`scripts/build-deps.sh benilla` clones Benilla, checks out that commit on a `wowmaccraft` branch,
applies the patches with `git am` and builds `build/benilla/target/release/benilla`, which
`scripts/package.sh` puts in the package as `wow/benilla`.

## What the patches add

- `crates/wowcraft-link`: the shared-memory link (the SkyCraft protocol v11 with WowCraft's
  extensions, 200 MB) as POSIX shared memory `/SkyCraft_v1.<uid>`, on the monotonic clock the
  patched mod reads (`CLOCK_UPTIME_RAW`).
- `crates/benilla-app/src/wowcraft/`, on only when the launcher starts WoW with `WOWCRAFT=1`:
  - `mod.rs`: the link's beat, who owns input, Minecraft's player driving ours (the controller's
    puppet branch in `player/puppet.rs`), teleports Minecraft must follow, the view and FOV;
  - `input.rs`: keyboard and mouse to Minecraft (SDL scancodes), WoW keeping C, Enter, M, L, B, O;
  - `account.rs`: the automatic login from the launcher's account file;
  - `collision.rs`: WoW's world (terrain, buildings, doodads) as Minecraft's collision, exact
    triangles and voxel masks, on a background thread;
  - `blocks.rs`: Minecraft's blocks, our Minecraft body, other entities, dropped items, arrows,
    cracks and the block outline drawn in WoW's world;
  - `overlay.rs`: Minecraft's HUD and screens over WoW's interface;
  - `water.rs`: WoW's water and lava as Minecraft's;
  - `combat.rs`: WoW's creatures as hittable stand-ins, Minecraft hits as WoW damage, WoW damage
    as Minecraft's, ore veins, herbs and stations as Minecraft blocks, kill loot;
  - `life.rs`: Minecraft's hearts as our life (god mode, death, release, revive);
  - `gifts.rs`: quest rewards, experience and each character's own Minecraft inventory;
  - `ui.rs`: WoW's interface under WowCraft (action bars, Log Out, cinematic, tutorials, name).
- Small hooks elsewhere in Benilla: the controller's puppet branch, the input gates in the
  bindings and the camera, Log Out refused, the automatic login and character creation helpers,
  and the chat filter for the module's own GM command answers.
