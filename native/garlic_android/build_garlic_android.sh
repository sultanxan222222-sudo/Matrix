#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${GARLIC_ANDROID_WORK:-${RUNNER_TEMP:-$ROOT/.build}/garlic-android}"
THIRD_PARTY="$WORK/third_party"
CLVK_DIR="$THIRD_PARTY/clvk"
CLSPV_DIR="$CLVK_DIR/external/clspv"
HOST_CLSPV_BUILD="$WORK/host-clspv"
ANDROID_BUILD="$WORK/clvk-android-arm64"
ANDROID_ABI="arm64-v8a"
ANDROID_API="26"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk/27.2.12479018" ]]; then
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
  elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
  else
    echo "ANDROID_NDK_HOME is not set and NDK 27.2.12479018 was not found." >&2
    exit 2
  fi
fi
if [[ ! -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]]; then
  echo "Invalid Android NDK path: $ANDROID_NDK_HOME" >&2
  exit 2
fi

mkdir -p "$THIRD_PARTY" \
  "$ROOT/app/src/main/assets/garlic" \
  "$ROOT/app/src/main/jniLibs/$ANDROID_ABI"

if [[ ! -d "$CLVK_DIR/.git" ]]; then
  git clone --depth 1 --recurse-submodules https://github.com/kpet/clvk.git "$CLVK_DIR"
else
  git -C "$CLVK_DIR" submodule update --init --recursive
fi

# MATRIX uses precompiled SPIR-V produced by host clspv. Patch clvk so its Android
# runtime accepts that IL directly instead of needing LLVM/clspv executables on-device.
python3 "$ROOT/native/garlic_android/patch_clvk_for_precompiled_il.py" "$CLVK_DIR"

# clvk pins compatible clspv, SPIR-V and OpenCL-Headers revisions in git submodules.
# clspv's LLVM/Clang/libclc source is fetched at the exact commit in its deps.json.
(
  cd "$CLSPV_DIR"
  python3 utils/fetch_sources.py --shallow
)

# Build clspv for the Linux host. clspv converts Garlic's OpenCL C source to portable SPIR-V.
cmake -S "$CLSPV_DIR" -B "$HOST_CLSPV_BUILD" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCLSPV_BUILD_TESTS=OFF \
  -DCLSPV_BUILD_SPIRV_DIS=OFF \
  -DENABLE_CLSPV_OPT=OFF \
  -DSKIP_CLSPV_INSTALL=ON \
  -DSKIP_CLSPV_TOOLS_INSTALL=ON
cmake --build "$HOST_CLSPV_BUILD" --target clspv --parallel "${GARLIC_BUILD_JOBS:-2}"

CLSPV_BIN="$(find "$HOST_CLSPV_BUILD" -type f -name clspv -perm -u+x -print -quit)"
if [[ -z "$CLSPV_BIN" ]]; then
  echo "The host clspv compiler was not produced." >&2
  exit 3
fi

# Compile a small end-to-end verification kernel, then the adapted Qwen3.5 decoder kernel set.
"$CLSPV_BIN" \
  "$ROOT/app/src/main/cpp/garlic/shaders/matrix_probe.cl" \
  -o "$ROOT/app/src/main/assets/garlic/matrix_probe.spv"
"$CLSPV_BIN" \
  "$ROOT/app/src/main/cpp/garlic/shaders/qwen35.cl" \
  -o "$ROOT/app/src/main/assets/garlic/qwen35.spv"

# clvk consumes the precompiled SPIR-V generated above. Since its Android compiler is
# disabled, it needs no LLVM/Clang, libclc or SPIRV-LLVM-Translator runtime linkage.

HOST_TAG="linux-x86_64"
SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/sysroot"
VULKAN_LIB="$(find "$SYSROOT/usr/lib/aarch64-linux-android" -name 'libvulkan.so' -type f -print -quit)"
if [[ -z "$VULKAN_LIB" || ! -f "$VULKAN_LIB" ]]; then
  echo "Android NDK Vulkan stub library is missing: $VULKAN_LIB" >&2
  exit 5
fi

cmake -S "$CLVK_DIR" -B "$ANDROID_BUILD" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_STL=c++_shared \
  -DCLVK_VULKAN_IMPLEMENTATION=custom \
  -DVulkan_INCLUDE_DIRS="$SYSROOT/usr/include" \
  -DVulkan_LIBRARY="$VULKAN_LIB" \
  -DVulkan_LIBRARIES="$VULKAN_LIB" \
  -DOPENCL_HEADERS_SOURCE_DIR="$CLVK_DIR/external/OpenCL-Headers" \
  -DSPIRV_HEADERS_SOURCE_DIR="$CLVK_DIR/external/SPIRV-Headers" \
  -DSPIRV_TOOLS_SOURCE_DIR="$CLVK_DIR/external/SPIRV-Tools" \
  -DCLSPV_SOURCE_DIR="$CLSPV_DIR" \
  -DCLVK_COMPILER_AVAILABLE=OFF \
  -DCLVK_ENABLE_SPIRV_IL=ON \
  -DCLVK_BUILD_SPIRV_TOOLS=ON \
  -DCLVK_BUILD_TESTS=OFF
cmake --build "$ANDROID_BUILD" --target OpenCL --parallel "${GARLIC_BUILD_JOBS:-2}"

OPENCL_LIB="$(find "$ANDROID_BUILD" -type f \( -name 'libOpenCL.so' -o -name 'libOpenCL.so.*' \) -print -quit)"
if [[ -z "$OPENCL_LIB" ]]; then
  echo "clvk did not produce libOpenCL.so." >&2
  exit 6
fi
cp "$OPENCL_LIB" "$ROOT/app/src/main/jniLibs/$ANDROID_ABI/libOpenCL.so"

echo "Garlic Android build inputs prepared:"
echo "  OpenCL runtime: $ROOT/app/src/main/jniLibs/$ANDROID_ABI/libOpenCL.so"
echo "  probe SPIR-V:   $ROOT/app/src/main/assets/garlic/matrix_probe.spv"
echo "  Qwen35 SPIR-V:  $ROOT/app/src/main/assets/garlic/qwen35.spv"
