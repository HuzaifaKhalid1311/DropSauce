// Decodes a JPEG XL image (first frame) into sRGB RGBA bytes, straight into an Android Bitmap.
// Built into app/src/main/jniLibs/*/libjxldec.so by build.sh; decoder-only libjxl, no encoder.
#include <algorithm>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <utility>

#include <jxl/cms.h>
#include <jxl/decode_cxx.h>
#include <jxl/thread_parallel_runner_cxx.h>

namespace {

size_t workerThreadCount() {
	return std::clamp<size_t>(JxlThreadParallelRunnerDefaultNumWorkerThreads(), 2, 4);
}

struct RunnerPool {
	static constexpr size_t kMaxIdle = 2;
	std::mutex mutex;
	JxlThreadParallelRunnerPtr idle[kMaxIdle];
	size_t count = 0;

	JxlThreadParallelRunnerPtr acquire() {
		std::lock_guard<std::mutex> lock(mutex);
		if (count > 0) {
			return std::move(idle[--count]);
		}
		return nullptr;
	}

	void release(JxlThreadParallelRunnerPtr runner) {
		if (!runner) return;
		std::lock_guard<std::mutex> lock(mutex);
		if (count < kMaxIdle) {
			idle[count++] = std::move(runner);
		}
	}
};

RunnerPool& runnerPool() {
	static RunnerPool pool;
	return pool;
}

struct ScopedRunner {
	JxlThreadParallelRunnerPtr runner;
	ScopedRunner() : runner(runnerPool().acquire()) {
		if (!runner) {
			runner = JxlThreadParallelRunnerMake(nullptr, workerThreadCount());
		}
	}
	~ScopedRunner() {
		runnerPool().release(std::move(runner));
	}
};

// Returns a width*height*4 buffer to decode into, or null to abort.
typedef uint8_t* (*AllocPixels)(void* ctx, uint32_t width, uint32_t height);

bool hasValidDimensions(const JxlBasicInfo& info) {
	return info.xsize > 0 && info.ysize > 0 && info.xsize <= INT32_MAX && info.ysize <= INT32_MAX;
}

bool decodeJxlBounds(const uint8_t* bytes, size_t size, uint32_t* width, uint32_t* height) {
	auto dec = JxlDecoderMake(nullptr);
	if (!dec) return false;
	if (JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO) != JXL_DEC_SUCCESS) return false;
	if (JxlDecoderSetInput(dec.get(), bytes, size) != JXL_DEC_SUCCESS) return false;
	JxlDecoderCloseInput(dec.get());
	JxlBasicInfo info = {};
	for (;;) {
		switch (JxlDecoderProcessInput(dec.get())) {
			case JXL_DEC_BASIC_INFO:
				if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS || !hasValidDimensions(info)) return false;
				*width = info.xsize;
				*height = info.ysize;
				return true;
			default:
				return false;
		}
	}
}

// On success the buffer from `alloc` holds premultiplied RGBA; `opaque` tells if alpha is all 255.
bool decodeJxl(const uint8_t* bytes, size_t size, AllocPixels alloc, void* ctx, bool* opaque) {
	auto dec = JxlDecoderMake(nullptr);
	ScopedRunner scopedRunner;
	if (!dec || !scopedRunner.runner) return false;
	if (JxlDecoderSetParallelRunner(dec.get(), JxlThreadParallelRunner, scopedRunner.runner.get()) != JXL_DEC_SUCCESS) return false;
	if (JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) != JXL_DEC_SUCCESS) return false;
	// Lets images with their own colour profile be converted to sRGB too
	JxlDecoderSetCms(dec.get(), *JxlGetDefaultCms());
	if (JxlDecoderSetInput(dec.get(), bytes, size) != JXL_DEC_SUCCESS) return false;
	JxlDecoderCloseInput(dec.get());

	const JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
	JxlBasicInfo info = {};
	uint8_t* pixels = nullptr;
	size_t length = 0;
	for (;;) {
		switch (JxlDecoderProcessInput(dec.get())) {
			case JXL_DEC_BASIC_INFO:
				if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS || !hasValidDimensions(info)) return false;
				break;
			case JXL_DEC_COLOR_ENCODING: {
				// Best effort: on failure the pixels stay in the image's own colour space
				JxlColorEncoding srgb = {};
				srgb.color_space = info.num_color_channels == 1 ? JXL_COLOR_SPACE_GRAY : JXL_COLOR_SPACE_RGB;
				srgb.white_point = JXL_WHITE_POINT_D65;
				srgb.primaries = JXL_PRIMARIES_SRGB;
				srgb.transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
				srgb.rendering_intent = JXL_RENDERING_INTENT_RELATIVE;
				JxlDecoderSetOutputColorProfile(dec.get(), &srgb, nullptr, 0);
				break;
			}
			case JXL_DEC_NEED_IMAGE_OUT_BUFFER:
				if (JxlDecoderImageOutBufferSize(dec.get(), &format, &length) != JXL_DEC_SUCCESS) return false;
				if (length != static_cast<size_t>(info.xsize) * info.ysize * 4) return false;
				pixels = alloc(ctx, info.xsize, info.ysize);
				if (!pixels) return false;
				if (JxlDecoderSetImageOutBuffer(dec.get(), &format, pixels, length) != JXL_DEC_SUCCESS) return false;
				break;
			case JXL_DEC_FULL_IMAGE:
				*opaque = info.alpha_bits == 0;
				if (info.alpha_bits != 0 && !info.alpha_premultiplied) {
					// Android bitmaps store premultiplied colour
					for (uint8_t* p = pixels; p != pixels + length; p += 4) {
						const uint32_t a = p[3];
						if (a == 255) continue;
						p[0] = static_cast<uint8_t>((p[0] * a + 127) / 255);
						p[1] = static_cast<uint8_t>((p[1] * a + 127) / 255);
						p[2] = static_cast<uint8_t>((p[2] * a + 127) / 255);
					}
				}
				return true;
			default: // error, truncated file, or finished without an image
				return false;
		}
	}
}

} // namespace

#ifdef __ANDROID__

#include <android/bitmap.h>
#include <jni.h>

namespace {

struct JniBitmapCache {
	jclass bitmapClass = nullptr;
	jmethodID createBitmap = nullptr;
	jmethodID setHasAlpha = nullptr;
	jobject argb8888 = nullptr;
};

const JniBitmapCache* getBitmapCache(JNIEnv* env) {
	static std::mutex cacheMutex;
	static JniBitmapCache cache;
	static bool initialized = false;
	std::lock_guard<std::mutex> lock(cacheMutex);
	if (!initialized) {
		jclass localBitmapClass = env->FindClass("android/graphics/Bitmap");
		jclass localConfigClass = env->FindClass("android/graphics/Bitmap$Config");
		if (localBitmapClass && localConfigClass) {
			jfieldID argbField = env->GetStaticFieldID(localConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
			jobject localArgb = argbField ? env->GetStaticObjectField(localConfigClass, argbField) : nullptr;
			cache.createBitmap = env->GetStaticMethodID(
				localBitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
			cache.setHasAlpha = env->GetMethodID(localBitmapClass, "setHasAlpha", "(Z)V");
			if (cache.createBitmap && cache.setHasAlpha && localArgb) {
				cache.bitmapClass = static_cast<jclass>(env->NewGlobalRef(localBitmapClass));
				cache.argb8888 = env->NewGlobalRef(localArgb);
				initialized = (cache.bitmapClass != nullptr && cache.argb8888 != nullptr);
			}
			if (localArgb) env->DeleteLocalRef(localArgb);
		}
		if (localBitmapClass) env->DeleteLocalRef(localBitmapClass);
		if (localConfigClass) env->DeleteLocalRef(localConfigClass);
	}
	return initialized ? &cache : nullptr;
}

struct BitmapTarget {
	JNIEnv* env;
	jobject bitmap;
	bool locked;
};

uint8_t* allocBitmap(void* ctx, uint32_t width, uint32_t height) {
	auto* target = static_cast<BitmapTarget*>(ctx);
	JNIEnv* env = target->env;
	const JniBitmapCache* cache = getBitmapCache(env);
	if (!cache) return nullptr;
	// Throws for absurd sizes or when out of memory: the exception stays pending for the caller
	target->bitmap = env->CallStaticObjectMethod(
		cache->bitmapClass, cache->createBitmap,
		static_cast<jint>(width), static_cast<jint>(height), cache->argb8888);
	if (env->ExceptionCheck() || !target->bitmap) return nullptr;
	AndroidBitmapInfo info;
	void* pixels = nullptr;
	if (AndroidBitmap_getInfo(env, target->bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.stride != static_cast<uint64_t>(width) * 4) return nullptr;
	if (AndroidBitmap_lockPixels(env, target->bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
	target->locked = true;
	return static_cast<uint8_t*>(pixels);
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_org_koitharu_kotatsu_core_image_JxlDecoder_decode(JNIEnv* env, jobject, jbyteArray data) {
	jbyte* bytes = env->GetByteArrayElements(data, nullptr);
	if (!bytes) return nullptr;
	BitmapTarget target = {env, nullptr, false};
	bool opaque = false;
	const bool ok = decodeJxl(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(data)), allocBitmap, &target, &opaque);
	if (target.locked) AndroidBitmap_unlockPixels(env, target.bitmap);
	env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
	if (!ok) return nullptr;
	if (opaque) {
		if (const JniBitmapCache* cache = getBitmapCache(env)) {
			env->CallVoidMethod(target.bitmap, cache->setHasAlpha, JNI_FALSE);
		}
	}
	return target.bitmap;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_koitharu_kotatsu_core_image_JxlDecoder_decodeBounds(JNIEnv* env, jobject, jbyteArray data) {
	jbyte* bytes = env->GetByteArrayElements(data, nullptr);
	if (!bytes) return nullptr;
	uint32_t width = 0, height = 0;
	const bool ok = decodeJxlBounds(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(data)), &width, &height);
	env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
	if (!ok) return nullptr;
	jintArray out = env->NewIntArray(2);
	if (!out) return nullptr;
	const jint dims[2] = {static_cast<jint>(width), static_cast<jint>(height)};
	env->SetIntArrayRegion(out, 0, 2, dims);
	return out;
}

#else // self-check on the computer: jxl_check in.jxl out.ppm

#include <fstream>
#include <iterator>
#include <vector>

namespace {

struct HostTarget {
	std::vector<uint8_t> pixels;
	uint32_t width, height;
};

uint8_t* allocHost(void* ctx, uint32_t width, uint32_t height) {
	auto* target = static_cast<HostTarget*>(ctx);
	target->width = width;
	target->height = height;
	target->pixels.resize(static_cast<size_t>(width) * height * 4);
	return target->pixels.data();
}

} // namespace

int main(int argc, char** argv) {
	if (argc < 3) return 2;
	std::ifstream in(argv[1], std::ios::binary);
	std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
	HostTarget target = {};
	bool opaque = false;
	if (!decodeJxl(bytes.data(), bytes.size(), allocHost, &target, &opaque)) {
		std::printf("decode failed\n");
		return 1;
	}
	std::printf("%ux%u opaque=%d\n", target.width, target.height, opaque);
	std::ofstream out(argv[2], std::ios::binary);
	out << "P6\n" << target.width << " " << target.height << "\n255\n";
	for (size_t i = 0; i < target.pixels.size(); i += 4) out.write(reinterpret_cast<const char*>(&target.pixels[i]), 3);
	return 0;
}

#endif
