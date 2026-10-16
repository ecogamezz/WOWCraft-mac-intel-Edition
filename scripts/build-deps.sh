#!/bin/sh
# Builds what WowMacCraft bundles from source, for an Intel Mac (macOS 12 or newer), into build/:
#   tools/   CMake 4.4.4 (universal) and the Temurin JDK 25 the mod patch needs
#   mariadb-install/   MariaDB 10.11.19 (server, client, admin)
#   vmangos-install/   vmangos (realmd, mangosd, the map/vmap/mmap tools), static against MariaDB's
#                      client and OpenSSL
#   benilla/   Benilla at the pinned commit with client/patches applied (the WowCraft client
#              module), built as build/benilla/target/release/benilla
#
# Needs: Xcode Command Line Tools, Rust (rustup), git, and Homebrew's openssl@3
# (`brew install openssl@3`; only its static libraries are linked in).
#
#   scripts/build-deps.sh [mariadb|vmangos|benilla|tools ...]   (default: all of them)
set -eu

here=$(cd "$(dirname "$0")/.." && pwd)
build="$here/build"
mkdir -p "$build/tools"
cd "$build"

CMAKE_VERSION=4.4.4
JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_mac_hotspot_25.0.4.1_1.tar.gz"
JDK_SHA256=e6229d9504f7922053ab31821b9e6bee8761daf7b026a3476d1a027563009880
MARIADB_VERSION=10.11.19
VMANGOS_COMMIT=4b3d241
BENILLA_COMMIT=$(cat "$here/client/BENILLA_COMMIT")
OPENSSL=${OPENSSL:-$(brew --prefix openssl@3 2>/dev/null || echo /usr/local/opt/openssl@3)}

cmake="$build/tools/cmake-$CMAKE_VERSION-macos-universal/CMake.app/Contents/bin/cmake"
sdk=$(xcrun --show-sdk-path)
# The SDK's own libc++ headers: an old Command Line Tools install leaves a stale copy in
# /Library/Developer/CommandLineTools/usr/include/c++/v1 that the compiler would find first.
cxxflags="-nostdinc++ -isystem $sdk/usr/include/c++/v1"
jobs=$(sysctl -n hw.ncpu)

tools() {
    if [ ! -x "$cmake" ]; then
        echo "== CMake $CMAKE_VERSION"
        curl -fL "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VERSION/cmake-$CMAKE_VERSION-macos-universal.tar.gz" | tar -xz -C tools
    fi
    if [ ! -d tools/jdk-25.0.4.1+1 ]; then
        echo "== Temurin JDK 25"
        curl -fL -o tools/jdk.tar.gz "$JDK_URL"
        echo "$JDK_SHA256  tools/jdk.tar.gz" | shasum -a 256 -c -
        tar -xzf tools/jdk.tar.gz -C tools && rm tools/jdk.tar.gz
    fi
}

mariadb() {
    tools
    echo "== MariaDB $MARIADB_VERSION"
    [ -d "mariadb-$MARIADB_VERSION" ] || curl -fL "https://archive.mariadb.org/mariadb-$MARIADB_VERSION/source/mariadb-$MARIADB_VERSION.tar.gz" | tar -xz
    rm -rf mariadb-build
    # No TLS for the client library (CONC_WITH_SSL=NONE: OFF reads as unset and wants GnuTLS);
    # libedit, not readline; none of the plugins WowCraft has no use for.
    "$cmake" -S "mariadb-$MARIADB_VERSION" -B mariadb-build \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$build/mariadb-install" \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 \
        -DCMAKE_OSX_SYSROOT="$sdk" "-DCMAKE_CXX_FLAGS=$cxxflags" \
        -DWITH_SSL=bundled -DCONC_WITH_SSL=NONE -DWITH_ZLIB=bundled -DWITH_PCRE=bundled \
        -DWITH_LIBFMT=bundled -DWITH_READLINE=OFF -DWITH_UNIT_TESTS=OFF -DWITH_EMBEDDED_SERVER=OFF \
        -DWITH_WSREP=OFF -DWITH_MARIABACKUP=OFF -DWITH_SAFEMALLOC=OFF -DWITH_JEMALLOC=no \
        -DPLUGIN_ROCKSDB=NO -DPLUGIN_MROONGA=NO -DPLUGIN_SPIDER=NO -DPLUGIN_CONNECT=NO \
        -DPLUGIN_COLUMNSTORE=NO -DPLUGIN_OQGRAPH=NO -DPLUGIN_S3=NO -DPLUGIN_SPHINX=NO \
        -DPLUGIN_AUTH_GSSAPI=NO -DPLUGIN_AUTH_GSSAPI_CLIENT=OFF -DPLUGIN_AUTH_PAM=NO \
        -DPLUGIN_AUTH_PAM_V1=NO -DPLUGIN_HASHICORP_KEY_MANAGEMENT=NO -DPLUGIN_PROVIDER_BZIP2=NO \
        -DPLUGIN_PROVIDER_LZ4=NO -DPLUGIN_PROVIDER_LZMA=NO -DPLUGIN_PROVIDER_LZO=NO \
        -DPLUGIN_PROVIDER_SNAPPY=NO
    "$cmake" --build mariadb-build -j"$jobs"
    "$cmake" --install mariadb-build
}

vmangos() {
    tools
    echo "== vmangos $VMANGOS_COMMIT"
    [ -f "$build/mariadb-install/lib/libmariadbclient.a" ] || mariadb
    [ -f "$OPENSSL/lib/libssl.a" ] || { echo "openssl@3 not found at $OPENSSL (brew install openssl@3)" >&2; exit 1; }
    if [ ! -d vmangos ]; then
        git clone https://github.com/vmangos/core.git vmangos
    fi
    git -C vmangos checkout -q "$VMANGOS_COMMIT"
    rm -rf vmangos-build
    "$cmake" -S vmangos -B vmangos-build \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$build/vmangos-install" \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 \
        -DCMAKE_OSX_SYSROOT="$sdk" "-DCMAKE_CXX_FLAGS=$cxxflags" \
        -DBUILD_EXTRACTORS=ON -DUSE_SCRIPTS=ON -DBUILD_FOR_HOST_CPU=OFF -DENABLE_CPPTRACE=OFF \
        -DDEBUG_SYMBOLS=OFF \
        -DMYSQL_INCLUDE_DIR="$build/mariadb-install/include/mysql" \
        -DMYSQL_LIBRARY="$build/mariadb-install/lib/libmariadbclient.a" \
        -DOPENSSL_INCLUDE_DIR="$OPENSSL/include" \
        "-DOPENSSL_LIBRARIES=$OPENSSL/lib/libssl.a;$OPENSSL/lib/libcrypto.a"
    "$cmake" --build vmangos-build -j"$jobs"
    "$cmake" --install vmangos-build
}

benilla() {
    echo "== Benilla $BENILLA_COMMIT + the WowCraft client module"
    if [ ! -d benilla ]; then
        git clone https://github.com/samwhosung/benilla.git benilla
    fi
    cd benilla
    if ! git rev-parse -q --verify refs/heads/wowmaccraft >/dev/null; then
        git checkout -q -b wowmaccraft "$BENILLA_COMMIT"
        git -c user.name=WowMacCraft -c user.email=wowmaccraft@localhost am -q "$here"/client/patches/*.patch
    fi
    cargo build --release -p benilla --no-default-features
    cd "$build"
}

[ $# -gt 0 ] || set -- tools mariadb vmangos benilla
for step in "$@"; do
    case "$step" in
        tools|mariadb|vmangos|benilla) "$step" ;;
        *) echo "unknown step: $step" >&2; exit 2 ;;
    esac
done
echo "== done"
