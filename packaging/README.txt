WowMacCraft: Minecraft inside World of Warcraft, on macOS (test build)
=======================================================================

Play on your own, host friends, or join a friend. Everything runs on your Mac:
the WoW server, its database and Minecraft. No ports to open, no accounts to make.

What you need
-------------
1. A WoW 1.12.1 client (the folder with a Data folder in it, from the Mac or the
   Windows client: only its game files are read, its program is never started).
2. Minecraft Java Edition (your own Microsoft account).
3. A Mac with macOS 12 or later (built for Intel; it runs on Apple silicon
   through Rosetta 2).
4. About 4 GB of free disk space (the server's maps are made from your WoW).

First start
-----------
1. Unzip this whole folder somewhere with space (your Games or Documents folder,
   not inside Applications).
2. Open WowMacCraft.app. If macOS says it can't be opened, right-click it, choose
   Open, then Open again (or allow it in System Settings, Privacy & Security).
3. Open Settings and choose your WoW 1.12 folder (Browse...).
4. Press PLAY. The first time:
   - the server reads its maps from your WoW: about 10-15 minutes, once;
   - Prism Launcher (included) opens and asks you to sign in with your
     Microsoft account, then downloads Minecraft and Java (a few minutes, once).
     If you already have Prism installed in Applications, that one is used;
   - WoW opens and logs in by itself; make a character, then Enter World.
   Creature pathfinding is then built in the background (about an hour, at the
   lowest priority); it's used from the next start. You can play meanwhile.

Playing with friends
--------------------
- Host co-op: like Play, and your join link appears in the launcher. Copy it
  and send it to your friends.
- Join: paste a friend's link, press Join. Their server makes your WoW account;
  WoW logs in by itself once Minecraft has joined them.

Controls: you play with Minecraft's controls inside WoW, with Minecraft's
HUD. C switches to WoW: its interface and the mouse cursor, Minecraft's HUD
hidden; C again switches back. Enter opens WoW chat; M the map, L the quest
log, B the bags and O WoW's menu switch to WoW too. E is Minecraft's
inventory, T its chat and Esc its menu.

Stop closes everything: WoW, Minecraft (it saves first) and the server (it
saves characters). Quitting the launcher does the same.

Your worlds and characters stay in this folder (mariadb/data, and Prism's
WowMacCraft instance). Keep the folder to keep your progress.

If something goes wrong
-----------------------
- "WoW 1.12 not found": choose the folder that has Data in it.
- The first setup stopped with an error: see server/data/*.log; press PLAY
  again to retry.
- Prism opens but Minecraft doesn't start: sign in (Accounts, top right), then
  press PLAY or Join again.
- WoW waits at the login screen: Minecraft isn't in its world yet (on Join:
  check the link, and that your friend is playing).
- Logs: wow/run_err.txt (WoW), server/logs (server), Prism's log for the
  WowMacCraft instance (Minecraft).
