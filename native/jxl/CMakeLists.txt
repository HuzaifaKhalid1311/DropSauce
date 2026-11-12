cmake_minimum_required(VERSION 3.22)
project(jxldec C CXX)

# Decoder-only libjxl, linked statically: everything the app never uses is off to keep the file small.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
foreach(opt TOOLS BENCHMARK EXAMPLES DOXYGEN MANPAGES JNI SJPEG OPENEXR TRANSCODE_JPEG BOXES)
	set(JPEGXL_ENABLE_${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(JPEGXL_ENABLE_LTO ON CACHE BOOL "" FORCE)
# Each CPU target is one more copy of the decoder. Dropped as pure size:
# - SVE*: 128-bit on phones, same as NEON (jxl-coder, used before, shipped without them too)
# - NEON: identical to NEON_WITHOUT_AES here, it only adds AES which the decoder never uses
foreach(target SVE SVE2 SVE2_128 NEON)
	set(JPEGXL_ENABLE_HWY_${target} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory(${LIBJXL_DIR} libjxl EXCLUDE_FROM_ALL)

if(ANDROID)
	add_library(jxldec SHARED jxl_jni.cpp)
	target_link_libraries(jxldec PRIVATE jxl_dec jxl_threads jxl_cms jnigraphics)
	target_link_options(jxldec PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--strip-all)
else()
	# Self-check on the computer: jxl_check in.jxl out.ppm
	add_executable(jxl_check jxl_jni.cpp)
	target_link_libraries(jxl_check PRIVATE jxl_dec jxl_threads jxl_cms)
endif()
