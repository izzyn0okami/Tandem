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

#ifndef LIBRETRODROID_ACHIEVEMENTS_H
#define LIBRETRODROID_ACHIEVEMENTS_H

#include <string>
#include <map>
#include <mutex>
#include <cstdint>
#include <cstddef>
#include <vector>

#include <jni.h>
#include <rc_client.h>
#include <rc_libretro.h>

namespace libretrodroid {

class Core;

/**
 * Motor de conquistas baseado no rc_client oficial da RetroAchievements (substitui o acesso
 * direto ao rc_runtime que existia antes). O rc_client cuida de login, identificar/carregar o
 * jogo, modo hardcore, e envio de desbloqueios com fila de novas tentativas se a internet cair;
 * por baixo dele continua rodando o mesmo rc_runtime de sempre, lendo a memória do núcleo a cada
 * quadro. A parte de rede (server_call) é só uma ponte: quem faz a chamada HTTP de verdade é o
 * Kotlin (RetroAchievements.kt), usando o OkHttp que o app já tem.
 */
class Achievements {
public:
    ~Achievements();

    /** Chamado uma vez (JNI_OnLoad) para guardar a JavaVM e conseguir chamar o Kotlin depois. */
    static void setJavaVM(JavaVM* vm);

    /** Objeto Kotlin que implementa as callbacks (ver RaCallbacks.java). Referência global. */
    void setCallbacks(JNIEnv* env, jobject callbacks);

    /** Cria (ou recria) o cliente rc_client para uma nova sessão de jogo. */
    void create();

    /** Libera o jogo e destrói o cliente rc_client ao sair da sessão. */
    void destroyClient();

    void setHardcoreEnabled(bool enabled);

    /** Login assíncrono; o resultado chega em RaCallbacks.onLoginResult. */
    void beginLoginWithToken(JNIEnv* env, const std::string& user, const std::string& token);

    /** Identifica e carrega o jogo pelo hash; resultado em RaCallbacks.onLoadGameResult. */
    void beginLoadGame(JNIEnv* env, Core* core, uint32_t consoleId, const struct retro_memory_map* mmap, const std::string& hash);

    void unloadGame();

    /** Processa as conquistas do quadro atual; chamar a cada quadro emulado. */
    void doFrame();

    bool isGameLoaded() const;

    /** Resposta HTTP que o Kotlin obteve para uma chamada pendente (ver onServerCall). */
    void deliverServerResponse(JNIEnv* env, int32_t requestId, int httpStatus, const std::string& body, bool networkError);

    /** Lista de conquistas do jogo atual, já em JSON (id, título, descrição, pontos, ícone, se já tem). */
    std::string getAchievementListJson();

    /** [desbloqueadas, total] do jogo atual. */
    std::string getSummaryJson();

    /** Serializa o progresso das conquistas, para gravar junto ao estado rápido do emulador. */
    std::vector<uint8_t> serializeProgress();

    /** Restaura o progresso das conquistas de um estado salvo; dados nulos reiniciam o progresso. */
 bool deserializeProgress(const uint8_t* data, size_t size);

    /** Reinicia o progresso das conquistas (o usuário reiniciou o jogo). */
    void reset();

    /** Processa eventos pendentes enquanto o jogo está pausado (no lugar de do_frame). */
    void idle();

    /** O hardcore permite pausar agora? Evita pausas seguidas para burlar condições de conquista. */
    bool canPause();

private:
    static uint32_t peekMemory(uint32_t address, uint8_t* buffer, uint32_t numBytes, rc_client_t* client);
    static void serverCallTrampoline(const rc_api_request_t* request, rc_client_server_callback_t callback, void* callbackData, rc_client_t* client);
    static void eventHandlerTrampoline(const rc_client_event_t* event, rc_client_t* client);
    static void loginCallbackTrampoline(int result, const char* errorMessage, rc_client_t* client, void* userdata);
    static void loadGameCallbackTrampoline(int result, const char* errorMessage, rc_client_t* client, void* userdata);

    void handleEvent(JNIEnv* env, const rc_client_event_t* event);
    void handleLoginResult(JNIEnv* env, int result, const char* errorMessage);
    void handleLoadGameResult(JNIEnv* env, int result, const char* errorMessage);

    rc_client_t* client = nullptr;
    jobject callbacksRef = nullptr;

    struct PendingCall {
        rc_client_server_callback_t callback;
        void* callbackData;
    };
    std::map<int32_t, PendingCall> pendingCalls;
    std::mutex pendingCallsMutex;
    int32_t nextRequestId = 1;

    rc_libretro_memory_regions_t memoryRegions = {};
    bool memoryInitialized = false;
};

}

#endif //LIBRETRODROID_ACHIEVEMENTS_H
