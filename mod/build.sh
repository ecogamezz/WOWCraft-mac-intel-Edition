#!/bin/sh
# Builds the macOS WowCraft mod: compiles MacNative against WowCraft's own jar, patches that jar's
# SkyLink onto it (tools/MacPatch.java), then runs the real SkyLink against POSIX shared memory
# (test/SkyLinkMacTest.java). Out: build/libs/wowcraft-<version>+mac.jar.
#
#   mod/build.sh <WowCraft's wowcraft-x.y.z.jar> [jdk home]
set -eu
here=$(cd "$(dirname "$0")" && pwd)
jar=${1:?usage: mod/build.sh wowcraft-x.y.z.jar [jdk-home]}
jdk=${2:-$here/../build/tools/jdk-25.0.4.1+1/Contents/Home}
name=$(basename "$jar" .jar)
out="$here/build/libs/$name+mac.jar"
libs="$here/build/libs"
mkdir -p "$libs"

# slf4j only for the test, which runs SkyLink without Minecraft.
for a in org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar org/slf4j/slf4j-simple/2.0.17/slf4j-simple-2.0.17.jar; do
	f="$libs/$(basename "$a")"
	if [ ! -f "$f" ]; then
		curl -sfL -o "$f" "https://repo1.maven.org/maven2/$a"
		echo "$(curl -sfL "https://repo1.maven.org/maven2/$a.sha1" | cut -c1-40)  $f" | shasum -a 1 -c -
	fi
done

rm -rf "$here/build/classes" "$here/build/stub"
mkdir -p "$here/build/classes" "$here/build/stub"
"$jdk/bin/javac" --release 25 -cp "$jar" -d "$here/build/classes" "$here/src/dev/skycraft/link/MacNative.java"
"$jdk/bin/java" "$here/tools/MacPatch.java" "$jar" "$out" "$here/build/classes"
"$jdk/bin/javac" --release 25 -cp "$libs/slf4j-api-2.0.17.jar" -d "$here/build/stub" "$here/test/stub/dev/skycraft/SkyCraft.java"
"$jdk/bin/java" --enable-native-access=ALL-UNNAMED \
	-cp "$here/build/stub:$libs/slf4j-api-2.0.17.jar:$libs/slf4j-simple-2.0.17.jar:$out" \
	"$here/test/SkyLinkMacTest.java"
echo "$out"
