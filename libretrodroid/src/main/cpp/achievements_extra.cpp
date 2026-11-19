#include <jni.h>
#include <vector>
#include "achievements.h"
#include "libretrodroid.h"

namespace libretrodroid {
uint32_t Achievements::getGameId() const {
    if (!client) return 0;
    const rc_client_game_t* game = rc_client_get_game_info(client);
    return game ? game->id : 0;
}

std::vector<uint8_t> Achievements::serializeProgress() {
    if (!client) return {};
    const uint32_t size = progressSize();
    if (size == 0) return {};
    std::vector<uint8_t> data(size);
    if (serializeProgress(data.data()) != RC_OK) return {};
    return data;
}

bool Achievements::deserializeProgress(const uint8_t* data, size_t size) {
    if (!client) return false;
    if (!data || size == 0) return deserializeProgress(nullptr) == RC_OK;
    return rc_client_deserialize_progress_sized(client, data, size) == RC_OK;
}
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raGetGameId(JNIEnv*, jclass) {
    return static_cast<jlong>(libretrodroid::LibretroDroid::getInstance().getAchievements().getGameId());
}

JNIEXPORT jstring JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raGetUserAgentClause(JNIEnv* env, jclass) {
    const auto clause = libretrodroid::LibretroDroid::getInstance().getAchievements().getUserAgentClause();
    return env->NewStringUTF(clause.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raIsProcessingRequired(JNIEnv*, jclass) {
    return libretrodroid::LibretroDroid::getInstance().getAchievements().isProcessingRequired() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raSetHardcoreEnabled(JNIEnv*, jclass, jboolean enabled) {
    libretrodroid::LibretroDroid::getInstance().getAchievements().setHardcoreEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raSerializeProgress(JNIEnv* env, jclass) {
    const auto data = libretrodroid::LibretroDroid::getInstance().getAchievements().serializeProgress();
    if (data.empty()) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raDeserializeProgress(JNIEnv* env, jclass, jbyteArray data) {
    auto& achievements = libretrodroid::LibretroDroid::getInstance().getAchievements();
    if (!data) return achievements.deserializeProgress(nullptr, 0) ? JNI_TRUE : JNI_FALSE;
    const jsize size = env->GetArrayLength(data);
    if (size <= 0) return achievements.deserializeProgress(nullptr, 0) ? JNI_TRUE : JNI_FALSE;
    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(buffer.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    return achievements.deserializeProgress(buffer.data(), buffer.size()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raReset(JNIEnv*, jclass) {
    libretrodroid::LibretroDroid::getInstance().getAchievements().reset();
}

JNIEXPORT void JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raIdle(JNIEnv*, jclass) {
    libretrodroid::LibretroDroid::getInstance().getAchievements().idle();
}

JNIEXPORT jboolean JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raCanPause(JNIEnv*, jclass) {
    return libretrodroid::LibretroDroid::getInstance().getAchievements().canPause() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
