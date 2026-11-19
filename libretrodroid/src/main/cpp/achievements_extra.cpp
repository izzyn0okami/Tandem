/*
 *     Copyright (C) 2026  Tandem
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

// Ponte JNI extra para os recursos de sessão do rc_client que faltavam:
// salvar/restaurar o progresso das conquistas junto ao estado do emulador, reiniciar o progresso
// quando o jogo é resetado, e manter o rc_client ativo enquanto o emulador está pausado.
// Fica num arquivo separado para não mexer no libretrodroidjni.cpp original (grande e já estável).

#include <jni.h>
#include <vector>

#include "libretrodroid.h"

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raSerializeProgress(JNIEnv* env, jclass) {
    using namespace libretrodroid;

    auto& achievements = LibretroDroid::getInstance().getAchievements();
    const uint32_t size = achievements.progressSize();
    if (size == 0) return nullptr;

    std::vector<uint8_t> buffer(size);
    if (achievements.serializeProgress(buffer.data()) != RC_OK) return nullptr;

    jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
    if (!array) return nullptr;
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(buffer.data()));
    return array;
}

JNIEXPORT jboolean JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raDeserializeProgress(JNIEnv* env, jclass, jbyteArray data) {
    using namespace libretrodroid;

    auto& achievements = LibretroDroid::getInstance().getAchievements();

    // Nulo = save antigo, sem progresso de conquistas: o rcheevos reinicializa o progresso.
    if (data == nullptr) {
        return achievements.deserializeProgress(nullptr) == RC_OK;
    }

    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return achievements.deserializeProgress(nullptr) == RC_OK;

    std::vector<uint8_t> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(buffer.data()));
    return achievements.deserializeProgress(buffer.data()) == RC_OK;
}

JNIEXPORT void JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raResetProgress(JNIEnv* env, jclass) {
    using namespace libretrodroid;
    LibretroDroid::getInstance().getAchievements().reset();
}

JNIEXPORT void JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raIdle(JNIEnv* env, jclass) {
    using namespace libretrodroid;
    LibretroDroid::getInstance().getAchievements().idle();
}

JNIEXPORT jboolean JNICALL
Java_com_swordfish_libretrodroid_LibretroDroid_raCanPause(JNIEnv* env, jclass) {
    using namespace libretrodroid;
    return LibretroDroid::getInstance().getAchievements().canPause();
}

} // extern "C"