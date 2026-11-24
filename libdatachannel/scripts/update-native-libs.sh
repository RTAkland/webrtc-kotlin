#!/usr/bin/env bash
#
# Rebuild libdatachannel static libraries and headers for all Kotlin/Native targets.
#
# Usage:
#   ./gradlew :libdatachannel:updateNativeLibs -PlibdatachannelVersion=0.24.7
#
# Direct usage:
#   bash libdatachannel/scripts/update-native-libs.sh --version 0.24.7
#   bash libdatachannel/scripts/update-native-libs.sh --version 0.24.7 \
#       --source /path/to/libdatachannel --mbedtls-source /path/to/mbedtls-3.6.2
#
# The script always builds with NO_MEDIA=ON and NO_WEBSOCKET=ON (this wrapper only
# exposes the DataChannel API), and uses the committed mbedtls 3.6.2 static libraries
# for the actual link. The mbedtls source tree is only needed for headers.
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
LIBDC_DIR="$ROOT_DIR/libdatachannel"
INCLUDE_DEST="$LIBDC_DIR/src/cinterop/include/libdatachannel"
LIBS_DEST="$LIBDC_DIR/src/cinterop/libs"

DEFAULT_VERSION="0.24.8"
MBEDTLS_VERSION="${MBEDTLS_VERSION:-3.6.2}"
ALL_TARGETS="linuxX64,linuxArm64,mingwX64,macosArm64"

VERSION="${LIBDATACHANNEL_VERSION:-$DEFAULT_VERSION}"
TARGETS="$ALL_TARGETS"
SOURCE_DIR=""
MBEDTLS_SOURCE_DIR="${MBEDTLS_SOURCE:-}"
WORK_DIR="$LIBDC_DIR/build/native-update"
JOBS="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
DOCKER_IMAGE="${OSXCROSS_IMAGE:-osxcross-env:latest}"
MACOS_MODE="${MACOS_MODE:-auto}"
KONAN_HOME="${KONAN_HOME:-$HOME/.konan}"
DRY_RUN=false
CLEAN=false

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

run() {
    if $DRY_RUN; then
        printf '+'
        printf ' %q' "$@"
        printf '\n'
        return 0
    fi
    printf '+'
    printf ' %q' "$@"
    printf '\n'
    "$@"
}

usage() {
    cat <<EOF
Rebuild libdatachannel static libraries and headers.

Options:
  --version <x.y.z>        libdatachannel version (default: $DEFAULT_VERSION)
  --targets <list>         Comma-separated: $ALL_TARGETS
  --source <dir>           Use an existing libdatachannel checkout with submodules
  --mbedtls-source <dir>   Use existing mbedtls $MBEDTLS_VERSION headers (no download)
  --work-dir <dir>         Build cache directory (default: $WORK_DIR)
  --jobs <n>               Parallel build jobs (default: $JOBS)
  --macos-mode <mode>      auto, native or docker (default: $MACOS_MODE)
  --clean                  Remove the work directory before starting
  --dry-run                Print commands without executing them
  -h, --help               Show this help

Environment:
  LIBDATACHANNEL_VERSION   Same as --version
  LIBDATACHANNEL_GIT_URL   Override the git clone URL
  MBEDTLS_VERSION          Fixed mbedtls version (default: $MBEDTLS_VERSION)
  MBEDTLS_SOURCE           Same as --mbedtls-source
  OSXCROSS_IMAGE           Docker image for macOS builds (default: $DOCKER_IMAGE)
  MACOS_MODE               Same as --macos-mode (default: $MACOS_MODE)
  KONAN_HOME               Kotlin/Native dependencies cache (default: $KONAN_HOME)

Notes:
  - On macOS, the macOS target is built natively with xcrun/Xcode by default.
  - On non-macOS hosts, the macOS target is cross-built in Docker with osxcross.
  - Windows x64 uses Kotlin/Native's bundled LLVM + MinGW sysroot so the .a files
    match the runtime that Kotlin/Native consumers link against.
  - WebSocket and media support are disabled (NO_WEBSOCKET=ON, NO_MEDIA=ON).
  - The committed mbedtls static libraries are reused; only headers are downloaded.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="${2:?--version requires a value}"; shift 2 ;;
        --version=*) VERSION="${1#*=}"; shift ;;
        --targets) TARGETS="${2:?--targets requires a value}"; shift 2 ;;
        --targets=*) TARGETS="${1#*=}"; shift ;;
        --source) SOURCE_DIR="${2:?--source requires a value}"; shift 2 ;;
        --source=*) SOURCE_DIR="${1#*=}"; shift ;;
        --mbedtls-source) MBEDTLS_SOURCE_DIR="${2:?--mbedtls-source requires a value}"; shift 2 ;;
        --mbedtls-source=*) MBEDTLS_SOURCE_DIR="${1#*=}"; shift ;;
        --work-dir) WORK_DIR="${2:?--work-dir requires a value}"; shift 2 ;;
        --work-dir=*) WORK_DIR="${1#*=}"; shift ;;
        --jobs) JOBS="${2:?--jobs requires a value}"; shift 2 ;;
        --jobs=*) JOBS="${1#*=}"; shift ;;
        --macos-mode) MACOS_MODE="${2:?--macos-mode requires a value}"; shift 2 ;;
        --macos-mode=*) MACOS_MODE="${1#*=}"; shift ;;
        --clean) CLEAN=true; shift ;;
        --dry-run) DRY_RUN=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1 (use --help)" ;;
    esac
done

VERSION="${VERSION#v}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "invalid version: $VERSION"
[[ "$JOBS" =~ ^[0-9]+$ ]] || die "invalid --jobs value: $JOBS"
case "$MACOS_MODE" in
    auto|native|docker) ;;
    *) die "invalid --macos-mode value: $MACOS_MODE (expected auto, native or docker)" ;;
esac

IFS=',' read -r -a TARGET_LIST <<< "$TARGETS"
[[ ${#TARGET_LIST[@]} -gt 0 ]] || die "no targets specified"
for target in "${TARGET_LIST[@]}"; do
    case "$target" in
        linuxX64|linuxArm64|mingwX64|macosArm64) ;;
        *) die "unknown target: $target (expected: $ALL_TARGETS)" ;;
    esac
done

require_tool() {
    command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"
}

# macOS ships BSD sort, which does not support -V.
version_sort() {
    if sort -V </dev/null >/dev/null 2>&1; then
        sort -V
    else
        sort
    fi
}

if $CLEAN && ! $DRY_RUN; then
    log "Cleaning $WORK_DIR"
    rm -rf "$WORK_DIR"
fi

mkdir -p "$WORK_DIR/src"

# ---------------------------------------------------------------------------
# Source code
# ---------------------------------------------------------------------------

if [[ -n "$SOURCE_DIR" ]]; then
    SRC="$SOURCE_DIR"
else
    SRC="$WORK_DIR/src/libdatachannel-$VERSION"
    if [[ ! -f "$SRC/CMakeLists.txt" ]]; then
        GIT_URL="${LIBDATACHANNEL_GIT_URL:-https://github.com/paullouisageneau/libdatachannel.git}"
        log "Cloning libdatachannel v$VERSION from $GIT_URL"
        run git clone --depth 1 --branch "v$VERSION" "$GIT_URL" "$SRC"
        log "Checking out required submodules (plog, usrsctp, libjuice)"
        # libsrtp is only needed for media and json only for examples/tests.
        run git -C "$SRC" submodule update --init --depth 1 deps/plog deps/usrsctp deps/libjuice
    fi
fi

[[ -f "$SRC/CMakeLists.txt" ]] || die "libdatachannel source not found at $SRC"
grep -q "VERSION $VERSION" "$SRC/CMakeLists.txt" || die "source at $SRC is not v$VERSION"
for dep in deps/plog deps/usrsctp deps/libjuice; do
    [[ -f "$SRC/$dep/CMakeLists.txt" ]] || die "missing submodule $dep in $SRC (run git submodule update --init $dep)"
done

# ---------------------------------------------------------------------------
# mbedtls headers (the static libraries are fixed at the committed version)
# ---------------------------------------------------------------------------

if [[ -n "$MBEDTLS_SOURCE_DIR" ]]; then
    MBEDTLS_SRC="$MBEDTLS_SOURCE_DIR"
else
    MBEDTLS_SRC="$WORK_DIR/src/mbedtls-$MBEDTLS_VERSION"
    if [[ ! -f "$MBEDTLS_SRC/include/mbedtls/build_info.h" ]]; then
        MBEDTLS_URL="${MBEDTLS_URL:-https://codeload.github.com/Mbed-TLS/mbedtls/tar.gz/refs/tags/v$MBEDTLS_VERSION}"
        archive="$WORK_DIR/src/mbedtls-$MBEDTLS_VERSION.tar.gz"
        log "Downloading mbedtls $MBEDTLS_VERSION headers"
        run curl -fL --retry 3 --connect-timeout 20 "$MBEDTLS_URL" -o "$archive"
        run tar -xzf "$archive" -C "$WORK_DIR/src"
    fi
fi

[[ -f "$MBEDTLS_SRC/include/mbedtls/build_info.h" ]] || die "mbedtls headers not found at $MBEDTLS_SRC"
grep -q "\"$MBEDTLS_VERSION\"" "$MBEDTLS_SRC/include/mbedtls/build_info.h" ||
    die "mbedtls headers at $MBEDTLS_SRC are not $MBEDTLS_VERSION"

# ---------------------------------------------------------------------------
# CMake configuration
# ---------------------------------------------------------------------------

# The committed mbedtls static libraries were built with different options per
# target. Match them exactly, otherwise the mbedtls context layouts differ and
# certificate/entropy initialization fails at runtime:
#   linux*  : DTLS-SRTP + pthread threading
#   mingwX64: DTLS-SRTP, no threading
#   macosArm64: DTLS-SRTP, no threading
mbedtls_defines_for_target() {
    case "$1" in
        linuxX64|linuxArm64) printf '%s' '-DMBEDTLS_SSL_DTLS_SRTP -DMBEDTLS_THREADING_C -DMBEDTLS_THREADING_PTHREAD' ;;
        mingwX64|macosArm64) printf '%s' '-DMBEDTLS_SSL_DTLS_SRTP' ;;
        *) die "unknown target for mbedtls defines: $1" ;;
    esac
}

# Kotlin/Native ships its own LLVM and a matching MinGW sysroot. Building the
# Windows libraries with that toolchain keeps them ABI-compatible with the
# libraries consumers link against, and avoids embedding host-specific -L paths.
find_konan_mingw_toolchain() {
    local llvm_dir
    local sysroot
    local gcc_dir
    # shellcheck disable=SC2012
    llvm_dir=$(ls -d "$KONAN_HOME"/dependencies/llvm-*/bin 2>/dev/null | version_sort | tail -1)
    # shellcheck disable=SC2012
    sysroot=$(ls -d "$KONAN_HOME"/dependencies/msys2-mingw-w64-x86_64-*/ 2>/dev/null | version_sort | tail -1)
    if [[ -z "$llvm_dir" || ! -x "$llvm_dir/clang++" || -z "$sysroot" ]]; then
        return 1
    fi
    # shellcheck disable=SC2012
    gcc_dir=$(ls -d "$sysroot"/lib/gcc/x86_64-w64-mingw32/* 2>/dev/null | version_sort | tail -1)
    if [[ -z "$gcc_dir" ]]; then
        return 1
    fi
    printf '%s\n' "$llvm_dir" "$sysroot" "$gcc_dir"
}

common_cmake_args() {
    local mbedtls_lib_dir="$1"
    local compile_flags="$2"
    printf '%s\n' \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DUSE_MBEDTLS=ON \
        -DUSE_GNUTLS=OFF \
        -DNO_MEDIA=ON \
        -DNO_WEBSOCKET=ON \
        -DNO_EXAMPLES=ON \
        -DNO_TESTS=ON \
        -DUSE_SYSTEM_JUICE=OFF \
        -DUSE_SYSTEM_USRSCTP=OFF \
        -DUSE_SYSTEM_PLOG=OFF \
        -DUSE_SYSTEM_JSON=OFF \
        -DUSE_SYSTEM_SRTP=OFF \
        -DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=ON \
        "-DMbedTLS_INCLUDE_DIR=$MBEDTLS_SRC/include" \
        "-DMbedTLS_LIBRARY=$mbedtls_lib_dir/libmbedtls.a" \
        "-DMbedX509_LIBRARY=$mbedtls_lib_dir/libmbedx509.a" \
        "-DMbedCrypto_LIBRARY=$mbedtls_lib_dir/libmbedcrypto.a"
    if [[ -n "$compile_flags" ]]; then
        printf '%s\n' "-DCMAKE_C_FLAGS=$compile_flags" "-DCMAKE_CXX_FLAGS=$compile_flags"
    fi
}

check_mbedtls_libs() {
    local mbedtls_lib_dir="$1"
    for lib in libmbedtls.a libmbedx509.a libmbedcrypto.a; do
        [[ -f "$mbedtls_lib_dir/$lib" ]] ||
            die "missing $mbedtls_lib_dir/$lib (mbedtls static libraries are fixed in this repo)"
    done
}

collect_outputs() {
    local build_dir="$1"
    local out_dir="$2"

    if $DRY_RUN; then
        log "Would collect outputs from $build_dir to $out_dir"
        return 0
    fi

    [[ -f "$build_dir/libdatachannel.a" ]] || die "libdatachannel.a not found in $build_dir"
    local juice
    local sctp
    juice=$(find "$build_dir/deps" -type f -name 'libjuice.a' | head -1)
    sctp=$(find "$build_dir/deps" -type f -name 'libusrsctp.a' | head -1)
    [[ -n "$juice" ]] || die "libjuice.a not found in $build_dir/deps"
    [[ -n "$sctp" ]] || die "libusrsctp.a not found in $build_dir/deps"

    mkdir -p "$out_dir"
    run cp "$build_dir/libdatachannel.a" "$out_dir/libdatachannel.a"
    run cp "$juice" "$out_dir/libjuice.a"
    run cp "$sctp" "$out_dir/libusrsctp.a"
    log "Collected $(basename "$out_dir"): $out_dir"
}

build_native_target() {
    local target="$1"
    local cc="$2"
    local cxx="$3"
    local compile_flags="$4"
    shift 4
    local extra_args=("$@")

    local mbedtls_lib_dir="$ROOT_DIR/mbedtls/src/cinterop/libs/$target"
    check_mbedtls_libs "$mbedtls_lib_dir"

    local build_dir="$WORK_DIR/build/$target"
    local out_dir="$WORK_DIR/out/$target"
    if ! $DRY_RUN; then
        rm -rf "$build_dir"
    fi
    mkdir -p "$build_dir"

    local defines
    defines="$(mbedtls_defines_for_target "$target")"
    if [[ -n "$compile_flags" ]]; then
        defines="${defines:+$defines }$compile_flags"
    fi

    local cmake_args=()
    while IFS= read -r line; do
        cmake_args+=("$line")
    done < <(common_cmake_args "$mbedtls_lib_dir" "$defines")
    if command -v ninja >/dev/null 2>&1; then
        cmake_args+=(-G Ninja)
    fi
    cmake_args+=(
        -S "$SRC"
        -B "$build_dir"
        "-DCMAKE_C_COMPILER=$cc"
        "-DCMAKE_CXX_COMPILER=$cxx"
    )
    if [[ ${#extra_args[@]} -gt 0 ]]; then
        cmake_args+=("${extra_args[@]}")
    fi

    log "Building $target"
    run cmake "${cmake_args[@]}"
    run cmake --build "$build_dir" --parallel "$JOBS"
    collect_outputs "$build_dir" "$out_dir"
}

build_macos_native_target() {
    local target="macosArm64"
    local mbedtls_lib_dir="$ROOT_DIR/mbedtls/src/cinterop/libs/$target"
    check_mbedtls_libs "$mbedtls_lib_dir"

    require_tool xcrun
    require_tool cmake
    require_tool make
    local cc
    local cxx
    local ar
    local ranlib
    local sdk
    cc=$(xcrun --find clang) || die "xcrun could not find clang"
    cxx=$(xcrun --find clang++) || die "xcrun could not find clang++"
    ar=$(xcrun --find ar) || die "xcrun could not find ar"
    ranlib=$(xcrun --find ranlib) || die "xcrun could not find ranlib"
    sdk=$(xcrun --sdk macosx --show-sdk-path) || die "xcrun could not find the macOS SDK"

    local build_dir="$WORK_DIR/build/$target"
    local out_dir="$WORK_DIR/out/$target"
    if ! $DRY_RUN; then
        rm -rf "$build_dir"
    fi
    mkdir -p "$build_dir"

    local cmake_args=()
    cmake_args+=(
        -S "$SRC"
        -B "$build_dir"
        -G "Unix Makefiles"
        -DCMAKE_SYSTEM_NAME=Darwin
        -DCMAKE_OSX_ARCHITECTURES=arm64
        "-DCMAKE_OSX_SYSROOT=$sdk"
        "-DCMAKE_C_COMPILER=$cc"
        "-DCMAKE_CXX_COMPILER=$cxx"
        "-DCMAKE_AR=$ar"
        "-DCMAKE_RANLIB=$ranlib"
    )
    while IFS= read -r line; do
        cmake_args+=("$line")
    done < <(common_cmake_args "$mbedtls_lib_dir" "$(mbedtls_defines_for_target "$target")")

    log "Building $target natively with Xcode"
    run cmake "${cmake_args[@]}"
    run cmake --build "$build_dir" --parallel "$JOBS"
    collect_outputs "$build_dir" "$out_dir"
}

build_macos_docker_target() {
    local target="macosArm64"
    local mbedtls_lib_dir="$ROOT_DIR/mbedtls/src/cinterop/libs/$target"
    check_mbedtls_libs "$mbedtls_lib_dir"

    require_tool docker
    if ! $DRY_RUN; then
        docker image inspect "$DOCKER_IMAGE" >/dev/null 2>&1 ||
            die "docker image '$DOCKER_IMAGE' not found (set OSXCROSS_IMAGE to override)"
    fi

    local cc
    local cxx
    local ar
    local ranlib
    cc=$(docker run --rm "$DOCKER_IMAGE" sh -c 'ls /osxcross/bin/aarch64-apple-darwin*-clang 2>/dev/null | head -1')
    [[ -n "$cc" ]] || die "aarch64 Apple clang not found in $DOCKER_IMAGE"
    cxx="${cc}++"
    ar=$(docker run --rm "$DOCKER_IMAGE" sh -c 'ls /osxcross/bin/aarch64-apple-darwin*-ar 2>/dev/null | head -1')
    ranlib=$(docker run --rm "$DOCKER_IMAGE" sh -c 'ls /osxcross/bin/aarch64-apple-darwin*-ranlib 2>/dev/null | head -1')
    [[ -n "$ar" ]] || die "aarch64 Apple ar not found in $DOCKER_IMAGE"
    [[ -n "$ranlib" ]] || die "aarch64 Apple ranlib not found in $DOCKER_IMAGE"

    local build_dir="$WORK_DIR/build/$target"
    local out_dir="$WORK_DIR/out/$target"
    if ! $DRY_RUN; then
        rm -rf "$build_dir"
    fi
    mkdir -p "$build_dir"

    local cmake_args=()
    # Trailing newlines are harmless in bash; keep the function output simple.
    cmake_args+=(
        -S "$SRC"
        -B "$build_dir"
        -G "Unix Makefiles"
        -DCMAKE_SYSTEM_NAME=Darwin
        -DCMAKE_OSX_ARCHITECTURES=arm64
        "-DCMAKE_C_COMPILER=$cc"
        "-DCMAKE_CXX_COMPILER=$cxx"
        "-DCMAKE_AR=$ar"
        "-DCMAKE_RANLIB=$ranlib"
    )
    local common=()
    while IFS= read -r line; do
        common+=("$line")
    done < <(common_cmake_args "$mbedtls_lib_dir" "$(mbedtls_defines_for_target "$target")")
    cmake_args+=("${common[@]}")

    local docker_script="$build_dir/build-in-docker.sh"
    if ! $DRY_RUN; then
        {
            printf '#!/usr/bin/env bash\n'
            printf 'set -euo pipefail\n'
            printf 'cmake'
            printf ' %q' "${cmake_args[@]}"
            printf '\n'
            printf 'cmake --build %q --parallel %q\n' "$build_dir" "$JOBS"
        } > "$docker_script"
        chmod +x "$docker_script"
    fi

    log "Building $target in Docker image $DOCKER_IMAGE"
    run docker run --rm \
        -u "$(id -u):$(id -g)" \
        -e HOME=/tmp \
        -v "$WORK_DIR:$WORK_DIR" \
        -v "$SRC:$SRC:ro" \
        -v "$MBEDTLS_SRC:$MBEDTLS_SRC:ro" \
        -v "$mbedtls_lib_dir:$mbedtls_lib_dir:ro" \
        -w /tmp \
        "$DOCKER_IMAGE" \
        bash "$docker_script"
    collect_outputs "$build_dir" "$out_dir"
}

build_macos_target() {
    local mode="$MACOS_MODE"
    if [[ "$mode" == auto ]]; then
        if [[ "$(uname -s)" == Darwin ]]; then
            mode=native
        else
            mode=docker
        fi
    fi
    case "$mode" in
        native) build_macos_native_target ;;
        docker) build_macos_docker_target ;;
        *) die "invalid macOS build mode: $mode" ;;
    esac
}

for target in "${TARGET_LIST[@]}"; do
    case "$target" in
        linuxX64)
            require_tool "${CC:-gcc}"
            require_tool "${CXX:-g++}"
            build_native_target "$target" "${CC:-gcc}" "${CXX:-g++}" ""
            ;;
        linuxArm64)
            require_tool aarch64-linux-gnu-gcc
            require_tool aarch64-linux-gnu-g++
            build_native_target "$target" aarch64-linux-gnu-gcc aarch64-linux-gnu-g++ "" \
                -DCMAKE_SYSTEM_NAME=Linux \
                -DCMAKE_SYSTEM_PROCESSOR=aarch64
            ;;
        mingwX64)
            konan_mingw=()
            while IFS= read -r line; do
                konan_mingw+=("$line")
            done < <(find_konan_mingw_toolchain || true)
            if [[ ${#konan_mingw[@]} -eq 3 ]]; then
                llvm_dir="${konan_mingw[0]}"
                win_sysroot="${konan_mingw[1]}"
                win_gcc_dir="${konan_mingw[2]}"
                win_cc="$llvm_dir/clang"
                win_cxx="$llvm_dir/clang++"
                [[ -x "$win_cc" && -x "$win_cxx" ]] ||
                    die "Kotlin/Native clang not found in $llvm_dir"
                win_compile_flags="--target=x86_64-w64-windows-gnu --sysroot=$win_sysroot -femulated-tls"
                win_extra_args=(
                    -DCMAKE_SYSTEM_NAME=Windows
                    -DCMAKE_SYSTEM_PROCESSOR=x86_64
                    "-DCMAKE_SYSROOT=$win_sysroot"
                    "-DCMAKE_FIND_ROOT_PATH=$win_sysroot"
                    "-DCMAKE_EXE_LINKER_FLAGS=-L$win_sysroot/x86_64-w64-mingw32/lib -L$win_gcc_dir"
                )
                if command -v x86_64-w64-mingw32-windres >/dev/null 2>&1; then
                    win_extra_args+=(-DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres)
                fi
                build_native_target "$target" "$win_cc" "$win_cxx" "$win_compile_flags" "${win_extra_args[@]}"
            else
                warn "Kotlin/Native MinGW sysroot not found under $KONAN_HOME; falling back to the system x86_64-w64-mingw32 toolchain."
                if command -v x86_64-w64-mingw32-gcc-posix >/dev/null 2>&1; then
                    win_cc=x86_64-w64-mingw32-gcc-posix
                    win_cxx=x86_64-w64-mingw32-g++-posix
                else
                    win_cc=x86_64-w64-mingw32-gcc
                    win_cxx=x86_64-w64-mingw32-g++
                fi
                require_tool "$win_cc"
                require_tool "$win_cxx"
                win_extra_args=(
                    -DCMAKE_SYSTEM_NAME=Windows
                    -DCMAKE_SYSTEM_PROCESSOR=x86_64
                )
                if command -v x86_64-w64-mingw32-windres >/dev/null 2>&1; then
                    win_extra_args+=(-DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres)
                fi
                build_native_target "$target" "$win_cc" "$win_cxx" "" "${win_extra_args[@]}"
            fi
            ;;
        macosArm64)
            build_macos_target
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Install headers and libraries only after every requested target succeeded
# ---------------------------------------------------------------------------

log "Installing headers for libdatachannel $VERSION"
# Git does not track the generated include/ directory, so create its parent
# before copying on a clean checkout.
run mkdir -p "$INCLUDE_DEST/include"
run rm -rf "$INCLUDE_DEST/include/rtc"
run cp -R "$SRC/include/rtc" "$INCLUDE_DEST/include/rtc"
run cp "$SRC/LICENSE" "$INCLUDE_DEST/LICENSE"
if ! $DRY_RUN; then
    printf '%s\n' "$VERSION" > "$INCLUDE_DEST/VERSION"
fi

for target in "${TARGET_LIST[@]}"; do
    out_dir="$WORK_DIR/out/$target"
    dest="$LIBS_DEST/$target"
    if ! $DRY_RUN; then
        mkdir -p "$dest"
    fi
    run cp "$out_dir/libdatachannel.a" "$dest/libdatachannel.a"
    run cp "$out_dir/libjuice.a" "$dest/libjuice.a"
    run cp "$out_dir/libusrsctp.a" "$dest/libusrsctp.a"
    log "Installed $target libraries"
done

if ! $DRY_RUN; then
    cat > "$INCLUDE_DEST/BUILDING.md" <<EOF
# Native libraries

- libdatachannel: v$VERSION
- mbedtls: $MBEDTLS_VERSION (fixed static libraries, only headers are downloaded)

Built with:

- \`NO_WEBSOCKET=ON\` (no WebSocket/signaling server support)
- \`NO_MEDIA=ON\` (DataChannel-only wrapper)
- \`NO_EXAMPLES=ON\`, \`NO_TESTS=ON\`
- \`BUILD_SHARED_LIBS=OFF\`
- \`USE_MBEDTLS=ON\`, \`USE_GNUTLS=OFF\`
- bundled \`libjuice\` and \`usrsctp\` submodules

Regenerate all libraries and headers with:

\`\`\`shell
./gradlew :libdatachannel:updateNativeLibs -PlibdatachannelVersion=$VERSION
\`\`\`

See \`libdatachannel/scripts/update-native-libs.sh\` for the exact toolchain commands.
EOF
fi

log "Done. libdatachannel $VERSION installed for: ${TARGET_LIST[*]}"
