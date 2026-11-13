cmake_minimum_required(VERSION 3.22)
project(jxldec C CXX)

# Decoder-only libjxl, linked statically: everything the app never uses is off to keep the file small.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
foreach(opt TOOLS BENCHMARK EXAMPLES DOXYGEN MANPAGES JNI SJPEG OPENEXR TRANSCODE_JPEG BOXES)
	set(JPEGXL_ENABLE_${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(JPEGXL_ENABLE_LTO ON CACHE BOOL "" FORCE)
# Each CPU target compiled into libjxldec is another copy of the codec loops.
# Keep one baseline 128-bit SIMD target per ABI so every Android device works with small binary size:
# - arm64-v8a: NEON_WITHOUT_AES (hardware ASIMD on every 64-bit ARM phone)
# - armeabi-v7a: EMU128 (Highway disables ARMv7 NEON without VFPv4 so non-VFPv4 devices stay supported)
# - x86 / x86_64: SSE2 (supported on all x86/x86_64 Android ABIs; avoids duplicate AVX2/SSE4 codegen)
foreach(target SVE SVE2 SVE2_128 NEON NEON_BF16 AVX2 AVX3 AVX3_DL AVX3_SPR AVX3_ZEN4 SSE4 EMU128 SCALAR)
	set(JPEGXL_ENABLE_HWY_${target} OFF CACHE BOOL "" FORCE)
endforeach()
if(ANDROID_ABI STREQUAL "armeabi-v7a")
	set(JPEGXL_ENABLE_HWY_EMU128 ON CACHE BOOL "" FORCE)
endif()
add_subdirectory(${LIBJXL_DIR} libjxl EXCLUDE_FROM_ALL)

# libjxl forces -funwind-tables and -fno-omit-frame-pointer in its root CMakeLists.txt;
# stripping unwind tables and frame pointers saves ~64-76 KB of .eh_frame per ABI.
foreach(tgt jxl_dec-obj jxl_dec jxl_threads jxl_cms hwy brotlidec brotlicommon)
	if(TARGET ${tgt})
		target_compile_options(${tgt} PRIVATE -fno-unwind-tables -fno-asynchronous-unwind-tables -fomit-frame-pointer)
	endif()
endforeach()

if(ANDROID)
	add_library(jxldec SHARED jxl_jni.cpp)
	target_compile_options(jxldec PRIVATE -fno-exceptions -fno-rtti -fno-unwind-tables -fno-asynchronous-unwind-tables -fomit-frame-pointer)
	target_link_libraries(jxldec PRIVATE jxl_dec jxl_threads jxl_cms jnigraphics)
	target_link_options(jxldec PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--strip-all)
else()
	# Self-check on the computer: jxl_check in.jxl out.ppm
	add_executable(jxl_check jxl_jni.cpp)
	target_link_libraries(jxl_check PRIVATE jxl_dec jxl_threads jxl_cms)
endif()
