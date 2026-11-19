/*
 *     Copyright (C) 2024  Argosy
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Implementações de progresso/pausa/reset das conquistas e os JNI wrappers correspondentes.
 * Ficam num arquivo separado para não mexer no libretrodroidjni.cpp já grande; o rc_client é o
 * mesmo criado em Achievements::create() (libretrodroidjni.cpp), então tudo compartilha o estado.
 */

#include <jni.h>
#include <vector>

#include "achievements.h"
#include "libretrodroid.h"

namespace libretrodroid {

std::vector<uint8_t> Achievements::serializeProgress() {
    if (!client) return {};
    const uint32_t size = rc_client_progress_size(client);
    if (size == 0) return {};

    std::vector<uint8_t> data(size);
    if (rc_client_serialize_progress(client, data.data()) != RC_OK) return {};
    return data;
}

bool Achievements::deserializeProgress(const uint8_t* data, size_t size) {
    if (!client) return false;

    // Save antigo (sem progresso de conquistas): o guia oficial manda reinicializar o progresso.
    if (!data || size == 0) {
        rc_client_deserialize_progress(client, nullptr);
        return true;
    }

    return rc_client_deserialize_progress(client, data) == RC_OK;
}

void Achievements::reset() {
    if (client) rc_client_reset(client);
}

void Achievements::idle() {
    if (client) rc_client_idle(client);
}

bool Achievements::canPause() {
    return client && rc_client_can_pause(client, nullptr) != 0;
}

} // namespace libretrodroid

extern "C" {

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

    if (!data) {
        return achievements.deserializeProgress(nullptr, 0) ? JNI_TRUE : JNI_FALSE;
    }

    const jsize size = env->GetArrayLength(data);
    if (size <= 0) {
        return achievements.deserializeProgress(nullptr, 0) ? JNI_TRUE : JNI_FALSE;
    }

    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(buffer.data()));
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
