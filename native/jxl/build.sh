#!/bin/sh
# Rebuilds app/src/main/jniLibs/*/libjxldec.so. Only needed to update the JPEG XL decoder:
# the built files are committed, so normal app builds need none of this.
#
# Usage: native/jxl/build.sh <tools dir>
# The tools dir holds: android-ndk-*/ (NDK), cmake/ (SDK CMake package, with ninja) and
# libjxl/ (source checkout with the highway, brotli and skcms submodules).
set -e
TOOLS=${1:?usage: build.sh <tools dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
CMAKE="$TOOLS/cmake/bin/cmake"
XYB_INL="$TOOLS/libjxl/lib/jxl/dec_xyb-inl.h"
if grep -q 'HWY_TARGET == HWY_NEON' "$XYB_INL"; then
	tmp=$(mktemp)
	sed 's/HWY_TARGET == HWY_NEON/HWY_TARGET_IS_NEON/g' "$XYB_INL" > "$tmp" && mv "$tmp" "$XYB_INL"
fi
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
	"$CMAKE" -S "$HERE" -B "$TOOLS/build/$abi" -G Ninja \
		-DCMAKE_MAKE_PROGRAM="$TOOLS/cmake/bin/ninja" \
		-DCMAKE_TOOLCHAIN_FILE="$(echo "$TOOLS"/android-ndk-*/build/cmake/android.toolchain.cmake)" \
		-DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 \
		-DCMAKE_BUILD_TYPE=MinSizeRel -DLIBJXL_DIR="$TOOLS/libjxl"
	"$CMAKE" --build "$TOOLS/build/$abi" --target jxldec
	mkdir -p "$HERE/../../app/src/main/jniLibs/$abi"
	cp "$TOOLS/build/$abi/libjxldec.so" "$HERE/../../app/src/main/jniLibs/$abi/"
done
ls -l "$HERE"/../../app/src/main/jniLibs/*/libjxldec.so
