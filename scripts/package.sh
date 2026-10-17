#!/bin/sh
# Assembles dist/WowMacCraft (and dist/WowMacCraft.zip) from what scripts/build-deps.sh made under
# build/: the launcher app, the WoW client (Benilla with the WowCraft module), the WoW server
# (vmangos), its database (MariaDB) and the Minecraft side (Prism Launcher and the instance).
#
# Two inputs come from elsewhere and are fetched once into build/:
# - WowCraft's own Windows release, for what is the same on every OS: the empty database
#   (`mariadb/data-clean`: InnoDB and Aria files open unchanged on macOS), the server's configs and
#   extractor data, and the Minecraft instance and its mods (jars, with WowCraft's patched for macOS);
# - Prism Launcher's official macOS build.
#
# A dist/WowMacCraft that has been played keeps its state, as an install that a newer package is
# unzipped over does: the package is assembled (and zipped) in dist/.new, and the old folder's
# own files move into it before it takes the old one's place (the end of this script).
#
#   scripts/package.sh [--no-zip]
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
build="$root/build"
dist="$root/dist"
install="$dist/WowMacCraft"
out="$dist/.new/WowMacCraft"
version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$root/launcher/Cargo.toml" | head -1)

# The launcher, the servers, Prism or Minecraft running from the install hold its state open.
in_use() {
	procs=$(ps -axww -o pid=,command=)
	if printf '%s\n' "$procs" | grep -F "$install/"; then
		echo "WowMacCraft is running from $install: quit it (Stop, then close the launcher) and build again"
		exit 1
	fi
}
in_use
# Only a rebuild cut short while swapping folders (the end of this script) leaves dist/.old.
if [ -e "$dist/.old" ]; then
	echo "$dist/.old is left from a rebuild cut short and may hold the install's state: move it back to $install"
	exit 1
fi
rm -rf "$dist/.new" "$dist/.trash"

wowcraft_tag=v0.1.1
prism_version=11.1.1

fetch() { # url file
	[ -f "$2" ] || { echo "fetching $1"; curl -sfL -o "$2.part" "$1" && mv "$2.part" "$2"; }
}

# ---- inputs ------------------------------------------------------------------------------------
mkdir -p "$build/inputs"
fetch "https://github.com/over825hp/wowcraft/releases/download/$wowcraft_tag/WowCraft.zip" "$build/inputs/WowCraft-$wowcraft_tag.zip"
if [ ! -d "$build/inputs/WowCraft-$wowcraft_tag" ]; then
	mkdir -p "$build/inputs/WowCraft-$wowcraft_tag"
	ditto -x -k "$build/inputs/WowCraft-$wowcraft_tag.zip" "$build/inputs/WowCraft-$wowcraft_tag"
fi
win="$build/inputs/WowCraft-$wowcraft_tag/WowCraft"
fetch "https://github.com/PrismLauncher/PrismLauncher/releases/download/$prism_version/PrismLauncher-macOS-$prism_version.zip" "$build/inputs/PrismLauncher-macOS-$prism_version.zip"
if [ ! -d "$build/inputs/prism-$prism_version" ]; then
	mkdir -p "$build/inputs/prism-$prism_version"
	ditto -x -k "$build/inputs/PrismLauncher-macOS-$prism_version.zip" "$build/inputs/prism-$prism_version"
fi
prism_app="$build/inputs/prism-$prism_version/Prism Launcher.app"
[ -d "$prism_app" ] || { echo "no Prism Launcher.app in the Prism download"; exit 1; }

benilla="$build/benilla/target/release/benilla"
vmangos="$build/vmangos-install"
maria="$build/mariadb-install"
for need in "$benilla" "$vmangos/bin/mangosd" "$maria/bin/mariadbd"; do
	[ -e "$need" ] || { echo "missing $need: run scripts/build-deps.sh first"; exit 1; }
done

# ---- the launcher ------------------------------------------------------------------------------
(cd "$root/launcher" && cargo build --release)
mkdir -p "$out"
app="$out/WowMacCraft.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$root/launcher/target/release/wowmaccraft" "$app/Contents/MacOS/wowmaccraft"
cp "$root/launcher/assets/icon.icns" "$app/Contents/Resources/icon.icns"
sed "s/@VERSION@/$version/g" "$root/launcher/assets/Info.plist" > "$app/Contents/Info.plist"

# ---- the WoW client ----------------------------------------------------------------------------
mkdir -p "$out/wow"
cp "$benilla" "$out/wow/benilla"

# ---- the WoW server ----------------------------------------------------------------------------
mkdir -p "$out/server/data"
cp "$vmangos/bin/mangosd" "$vmangos/bin/realmd" "$out/server/"
for t in MapExtractor VMapExtractor VMapAssembler MoveMapGenerator; do
	cp "$vmangos/bin/Extractors/$t" "$out/server/"
done
# WowCraft's configs (vmangos' own, pointed at the packaged database): Unix line ends.
for c in mangosd.conf realmd.conf; do
	tr -d '\r' < "$win/server/$c" > "$out/server/$c"
done
cp "$vmangos/bin/Extractors/config.json" "$vmangos/bin/Extractors/offmesh.txt" "$out/server/data/"

# ---- the database ------------------------------------------------------------------------------
mkdir -p "$out/mariadb/bin" "$out/mariadb/share" "$out/mariadb/lib/plugin"
for b in mariadbd mariadb mariadb-admin; do
	cp "$maria/bin/$b" "$out/mariadb/bin/"
done
cp -R "$maria/share/english" "$maria/share/charsets" "$out/mariadb/share/"
cp "$maria/share/errmsg-utf8.txt" "$out/mariadb/share/" 2>/dev/null || true
cp -R "$maria/lib/plugin/." "$out/mariadb/lib/plugin/" 2>/dev/null || true
ditto "$win/mariadb/data-clean" "$out/mariadb/data-clean"

# ---- Minecraft ---------------------------------------------------------------------------------
inst="$out/minecraft/WowMacCraft"
mkdir -p "$inst/minecraft/mods" "$inst/minecraft/config"
sed -e 's/^name=WowCraft$/name=WowMacCraft/' \
	-e "s/^notes=.*/notes=WowMacCraft: Minecraft inside WoW. WowMacCraft.app starts this; you don't need to open it here./" \
	"$win/minecraft/WowCraft/instance.cfg" > "$inst/instance.cfg"
cp "$win/minecraft/WowCraft/mmc-pack.json" "$inst/"
cp "$win/minecraft/WowCraft/minecraft/config/"* "$inst/minecraft/config/"
for j in "$win/minecraft/WowCraft/minecraft/mods/"*.jar; do
	case "$(basename "$j")" in
		wowcraft-*) sh "$root/mod/build.sh" "$j" > /dev/null
			cp "$root/mod/build/libs/$(basename "$j" .jar)+mac.jar" "$inst/minecraft/mods/" ;;
		*) cp "$j" "$inst/minecraft/mods/" ;;
	esac
done

# ---- Prism Launcher ----------------------------------------------------------------------------
mkdir -p "$out/prism"
ditto "$prism_app" "$out/prism/Prism Launcher.app"
cp "$win/prism/prismlauncher.cfg" "$out/prism/prismlauncher.cfg"
sed -e 's/Prism Launcher 11.1.1 (portable, unmodified)/Prism Launcher '"$prism_version"' for macOS (unmodified)/' \
	-e 's/WowCraft/WowMacCraft/g' -e 's/licenses\\PrismLauncher-LICENSE/licenses\/PrismLauncher-LICENSE/' \
	-e 's/(tag 11.1.1)/(tag '"$prism_version"')/' -e 's/Only prismlauncher.cfg (language, theme, automatic Java) was added by WowMacCraft./Only prismlauncher.cfg (language, theme, automatic Java) was added by WowMacCraft; it is copied into prism\/data, where this Prism keeps its sign-in and instances./' \
	"$win/prism/NOTICE-WowCraft.txt" > "$out/prism/NOTICE-WowMacCraft.txt"

# ---- licences and the readme -------------------------------------------------------------------
mkdir -p "$out/licenses"
cp "$win/licenses/"*LICENSE* "$win/licenses/MariaDB-COPYING" "$out/licenses/"
cp "$root/LICENSE" "$out/licenses/WowMacCraft-LICENSE"
cp "$root/packaging/NOTICE.txt" "$out/licenses/NOTICE.txt"
cp "$root/packaging/README.txt" "$out/README.txt"

# Ad-hoc signatures: what macOS wants of every program it runs (Apple silicon refuses one without).
codesign --force --sign - "$out/wow/benilla" "$out/server/"* "$out/mariadb/bin/"* 2>/dev/null || true
codesign --force --sign - "$app"

# The zip is the package alone: made before a played install's files join it.
if [ "${1:-}" != "--no-zip" ]; then
	rm -f "$dist/WowMacCraft.zip"
	(cd "$dist/.new" && ditto -c -k --keepParent WowMacCraft "$dist/WowMacCraft.zip")
	echo "$dist/WowMacCraft.zip ($(du -h "$dist/WowMacCraft.zip" | cut -f1))"
fi

# ---- the install -------------------------------------------------------------------------------
# A played install's own files move into the new package (renames, so nothing is copied or
# changed): the launcher's settings, the database (made from data-clean on the first Play), the
# server's maps (made from the player's WoW; beside the package's own config.json and offmesh.txt
# in server/data) and logs, the bundled Prism's data (the Microsoft sign-in, the instance with its
# worlds) and the WoW client's settings and logs. The rest of the old install goes.
in_use
trap '' HUP INT TERM
if [ -d "$install" ]; then
	mv "$install" "$dist/.old"
	for p in wowmaccraft-launcher.cfg wowmaccraft-server.log mariadb/data server/logs prism/data \
		wow/benilla-config wow/run_out.txt wow/run_err.txt; do
		if [ -e "$dist/.old/$p" ]; then
			rm -rf "$out/$p"
			mv "$dist/.old/$p" "$out/$p"
		fi
	done
	for p in "$dist/.old/server/data/"*; do
		if [ -e "$p" ] && [ ! -e "$out/server/data/$(basename "$p")" ]; then
			mv "$p" "$out/server/data/"
		fi
	done
	mv "$dist/.old" "$dist/.trash"
fi
mv "$out" "$install"
trap - HUP INT TERM
rm -rf "$dist/.new" "$dist/.trash"
echo "$install"
