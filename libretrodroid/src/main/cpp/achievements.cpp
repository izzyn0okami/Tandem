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

#include "achievements.h"
#include "libretrodroid.h"
#include "log.h"

#include <cstring>
#include <sstream>

namespace libretrodroid {

static Core* g_core = nullptr;
static JavaVM* g_jvm = nullptr;

// Declarado aqui (definido mais abaixo) porque o bloco de callbacks logo a seguir já precisa dele.
static std::string jsonEscape(const char* raw);

// Nomes/assinaturas do lado Kotlin (ver RaCallbacks.java). Resolvidos uma vez e guardados aqui
// porque todo o resto do código nativo chama de dentro de Achievements, sempre a partir de uma
// entrada JNI (então sempre temos um JNIEnv* válido da própria chamada, nunca precisamos anexar
// uma thread nova à JVM).
namespace {
jclass g_callbacksClass = nullptr;
jmethodID g_onServerCall = nullptr;
jmethodID g_onLoginResult = nullptr;
jmethodID g_onLoadGameResult = nullptr;
jmethodID g_onAchievementTriggered = nullptr;
jmethodID g_onServerError = nullptr;
jmethodID g_onReset = nullptr;
// Indicadores e avisos extras, no padrão de overlay da própria RetroAchievements
// (https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration): desafio ativo,
// indicador de progresso, placares (leaderboards) e o aviso de "Mastered"/"Completed".
jmethodID g_onChallengeIndicatorShow = nullptr;
jmethodID g_onChallengeIndicatorHide = nullptr;
jmethodID g_onProgressIndicatorShow = nullptr;
jmethodID g_onProgressIndicatorHide = nullptr;
jmethodID g_onLeaderboardStarted = nullptr;
jmethodID g_onLeaderboardFailed = nullptr;
jmethodID g_onLeaderboardSubmitted = nullptr;
jmethodID g_onLeaderboardTrackerUpdate = nullptr;
jmethodID g_onLeaderboardTrackerHide = nullptr;
jmethodID g_onGameCompleted = nullptr;

void resolveCallbackMethods(JNIEnv* env, jobject callbacks) {
    if (g_callbacksClass) return;
    jclass local = env->GetObjectClass(callbacks);
    g_callbacksClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    g_onServerCall = env->GetMethodID(
        g_callbacksClass, "onServerCall",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    );
    g_onLoginResult = env->GetMethodID(g_callbacksClass, "onLoginResult", "(ZLjava/lang/String;)V");
    g_onLoadGameResult = env->GetMethodID(g_callbacksClass, "onLoadGameResult", "(ZLjava/lang/String;)V");
    g_onAchievementTriggered = env->GetMethodID(g_callbacksClass, "onAchievementTriggered", "(I)V");
    g_onServerError = env->GetMethodID(g_callbacksClass, "onServerError", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onReset = env->GetMethodID(g_callbacksClass, "onReset", "()V");
    g_onChallengeIndicatorShow = env->GetMethodID(g_callbacksClass, "onChallengeIndicatorShow", "(ILjava/lang/String;)V");
    g_onChallengeIndicatorHide = env->GetMethodID(g_callbacksClass, "onChallengeIndicatorHide", "(I)V");
    g_onProgressIndicatorShow = env->GetMethodID(
        g_callbacksClass, "onProgressIndicatorShow",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;F)V"
    );
    g_onProgressIndicatorHide = env->GetMethodID(g_callbacksClass, "onProgressIndicatorHide", "()V");
    g_onLeaderboardStarted = env->GetMethodID(g_callbacksClass, "onLeaderboardStarted", "(ILjava/lang/String;Ljava/lang/String;)V");
    g_onLeaderboardFailed = env->GetMethodID(g_callbacksClass, "onLeaderboardFailed", "(ILjava/lang/String;)V");
    g_onLeaderboardSubmitted = env->GetMethodID(g_callbacksClass, "onLeaderboardSubmitted", "(ILjava/lang/String;Ljava/lang/String;)V");
    g_onLeaderboardTrackerUpdate = env->GetMethodID(g_callbacksClass, "onLeaderboardTrackerUpdate", "(ILjava/lang/String;)V");
    g_onLeaderboardTrackerHide = env->GetMethodID(g_callbacksClass, "onLeaderboardTrackerHide", "(I)V");
    g_onGameCompleted = env->GetMethodID(g_callbacksClass, "onGameCompleted", "(Ljava/lang/String;)V");
}

// Monta o aviso de "Mastered"/"Completed" (jogo todo ou só um subconjunto/DLC): usa sempre a arte
// do jogo principal, já que a rc_client não expõe uma URL de ícone própria para subconjuntos.
std::string buildGameCompletedJson(rc_client_t* client, const rc_client_subset_t* subset) {
    const rc_client_game_t* game = rc_client_get_game_info(client);
    char urlBuffer[192] = {0};
    if (game) rc_client_game_get_image_url(game, urlBuffer, sizeof(urlBuffer));
    bool hardcore = rc_client_get_hardcore_enabled(client) != 0;
    const char* title = subset ? subset->title : (game ? game->title : "");

    std::ostringstream json;
    json << "{"
         << "\"title\":\"" << jsonEscape(title) << "\","
         << "\"badgeUrl\":\"" << jsonEscape(urlBuffer) << "\","
         << "\"hardcore\":" << (hardcore ? "true" : "false") << ","
         << "\"subset\":" << (subset ? "true" : "false")
         << "}";
    return json.str();
}
}

static void getCoreMemoryInfo(uint32_t id, rc_libretro_core_memory_info_t* info) {
    if (!g_core || !info) return;
    info->data = static_cast<uint8_t*>(g_core->retro_get_memory_data(id));
    info->size = g_core->retro_get_memory_size(id);
}

static std::string jsonEscape(const char* raw) {
    if (!raw) return "";
    std::string out;
    for (const char* p = raw; *p; ++p) {
        switch (*p) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': break;
            default:
                if (static_cast<unsigned char>(*p) < 0x20) continue;
                out += *p;
        }
    }
    return out;
}

void Achievements::setJavaVM(JavaVM* vm) {
    g_jvm = vm;
}

Achievements::~Achievements() {
    destroyClient();
}

void Achievements::setCallbacks(JNIEnv* env, jobject callbacks) {
    if (callbacksRef) {
        env->DeleteGlobalRef(callbacksRef);
        callbacksRef = nullptr;
    }
    if (callbacks) {
        callbacksRef = env->NewGlobalRef(callbacks);
        resolveCallbackMethods(env, callbacksRef);
    }
}

uint32_t Achievements::peekMemory(uint32_t address, uint8_t* buffer, uint32_t numBytes, rc_client_t* client) {
    auto* self = static_cast<Achievements*>(rc_client_get_userdata(client));
    if (self && self->memoryInitialized) {
        uint32_t bytesRead = rc_libretro_memory_read(&self->memoryRegions, address, buffer, numBytes);
        if (bytesRead > 0) return bytesRead;
    }

    if (!g_core) return 0;
    void* memPtr = g_core->retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
    size_t memSize = g_core->retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
    if (!memPtr || address + numBytes > memSize) return 0;

    memcpy(buffer, static_cast<uint8_t*>(memPtr) + address, numBytes);
    return numBytes;
}

void Achievements::serverCallTrampoline(
    const rc_api_request_t* request,
    rc_client_server_callback_t callback,
    void* callbackData,
    rc_client_t* client
) {
    auto* self = static_cast<Achievements*>(rc_client_get_userdata(client));
    if (!self || !g_jvm || !self->callbacksRef || !g_onServerCall) {
        rc_api_server_response_t response = {};
        response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        callback(&response, callbackData);
        return;
    }

    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        rc_api_server_response_t response = {};
        response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        callback(&response, callbackData);
        return;
    }

    int32_t requestId;
    {
        std::lock_guard<std::mutex> lock(self->pendingCallsMutex);
        requestId = self->nextRequestId++;
        self->pendingCalls[requestId] = {callback, callbackData};
    }

    jstring jUrl = env->NewStringUTF(request->url ? request->url : "");
    jstring jPostData = request->post_data ? env->NewStringUTF(request->post_data) : nullptr;
    jstring jContentType = request->content_type ? env->NewStringUTF(request->content_type) : nullptr;

    env->CallVoidMethod(self->callbacksRef, g_onServerCall, requestId, jUrl, jPostData, jContentType);

    env->DeleteLocalRef(jUrl);
    if (jPostData) env->DeleteLocalRef(jPostData);
    if (jContentType) env->DeleteLocalRef(jContentType);
}

void Achievements::deliverServerResponse(JNIEnv* env, int32_t requestId, int httpStatus, const std::string& body, bool networkError) {
    PendingCall pending;
    {
        std::lock_guard<std::mutex> lock(pendingCallsMutex);
        auto it = pendingCalls.find(requestId);
        if (it == pendingCalls.end()) return;
        pending = it->second;
        pendingCalls.erase(it);
    }

    rc_api_server_response_t response = {};
    // rcheevos distinguishes a transport failure (NULL body) from an HTTP response with an empty body.
    response.body = networkError ? nullptr : body.c_str();
    response.body_length = networkError ? 0 : body.size();
    response.http_status_code = networkError ? RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR : httpStatus;

    pending.callback(&response, pending.callbackData);
}

void Achievements::handleEvent(JNIEnv* env, const rc_client_event_t* event) {
    if (!callbacksRef) return;

    switch (event->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
            if (event->achievement && g_onAchievementTriggered) {
                LOGI("Achievement TRIGGERED: %u", event->achievement->id);
                env->CallVoidMethod(callbacksRef, g_onAchievementTriggered, static_cast<jint>(event->achievement->id));
            }
            break;
        case RC_CLIENT_EVENT_SERVER_ERROR:
            if (event->server_error && g_onServerError) {
                jstring api = env->NewStringUTF(event->server_error->api ? event->server_error->api : "");
                jstring message = env->NewStringUTF(event->server_error->error_message ? event->server_error->error_message : "");
                env->CallVoidMethod(callbacksRef, g_onServerError, api, message);
                env->DeleteLocalRef(api);
                env->DeleteLocalRef(message);
            }
            break;
        case RC_CLIENT_EVENT_RESET:
            // O rcheevos pede o reinício do núcleo (normalmente ao mudar de hardcore com o jogo
            // aberto). O Kotlin repassa para a Activity, que reinicia o emulador.
            LOGI("RetroAchievements pediu reset do núcleo (RC_CLIENT_EVENT_RESET)");
            if (g_onReset) env->CallVoidMethod(callbacksRef, g_onReset);
            break;
        case RC_CLIENT_EVENT_DISCONNECTED:
            LOGW("RetroAchievements: sem conexão, desbloqueios pendentes serão reenviados");
            break;
        case RC_CLIENT_EVENT_RECONNECTED:
            LOGI("RetroAchievements: conexão de volta, desbloqueios pendentes reenviados");
            break;

        // --- Indicadores e avisos extras, padrão RetroAchievements (rc_client-integration) -------

        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW:
            if (event->achievement && g_onChallengeIndicatorShow) {
                jstring badge = env->NewStringUTF(event->achievement->badge_name);
                env->CallVoidMethod(callbacksRef, g_onChallengeIndicatorShow, static_cast<jint>(event->achievement->id), badge);
                env->DeleteLocalRef(badge);
            }
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE:
            if (event->achievement && g_onChallengeIndicatorHide) {
                env->CallVoidMethod(callbacksRef, g_onChallengeIndicatorHide, static_cast<jint>(event->achievement->id));
            }
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE:
            if (event->achievement && g_onProgressIndicatorShow) {
                jstring title = env->NewStringUTF(event->achievement->title ? event->achievement->title : "");
                jstring badge = env->NewStringUTF(event->achievement->badge_name);
                jstring progress = env->NewStringUTF(event->achievement->measured_progress);
                env->CallVoidMethod(
                    callbacksRef, g_onProgressIndicatorShow,
                    static_cast<jint>(event->achievement->id), title, badge, progress,
                    static_cast<jfloat>(event->achievement->measured_percent)
                );
                env->DeleteLocalRef(title);
                env->DeleteLocalRef(badge);
                env->DeleteLocalRef(progress);
            }
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE:
            if (g_onProgressIndicatorHide) env->CallVoidMethod(callbacksRef, g_onProgressIndicatorHide);
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_STARTED:
            if (event->leaderboard && g_onLeaderboardStarted) {
                jstring title = env->NewStringUTF(event->leaderboard->title ? event->leaderboard->title : "");
                jstring description = env->NewStringUTF(event->leaderboard->description ? event->leaderboard->description : "");
                env->CallVoidMethod(callbacksRef, g_onLeaderboardStarted, static_cast<jint>(event->leaderboard->id), title, description);
                env->DeleteLocalRef(title);
                env->DeleteLocalRef(description);
            }
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_FAILED:
            if (event->leaderboard && g_onLeaderboardFailed) {
                jstring title = env->NewStringUTF(event->leaderboard->title ? event->leaderboard->title : "");
                env->CallVoidMethod(callbacksRef, g_onLeaderboardFailed, static_cast<jint>(event->leaderboard->id), title);
                env->DeleteLocalRef(title);
            }
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED:
            if (event->leaderboard && g_onLeaderboardSubmitted) {
                jstring title = env->NewStringUTF(event->leaderboard->title ? event->leaderboard->title : "");
                jstring score = env->NewStringUTF(event->leaderboard->tracker_value ? event->leaderboard->tracker_value : "");
                env->CallVoidMethod(callbacksRef, g_onLeaderboardSubmitted, static_cast<jint>(event->leaderboard->id), title, score);
                env->DeleteLocalRef(title);
                env->DeleteLocalRef(score);
            }
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE:
            if (event->leaderboard_tracker && g_onLeaderboardTrackerUpdate) {
                jstring display = env->NewStringUTF(event->leaderboard_tracker->display);
                env->CallVoidMethod(callbacksRef, g_onLeaderboardTrackerUpdate, static_cast<jint>(event->leaderboard_tracker->id), display);
                env->DeleteLocalRef(display);
            }
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE:
            if (event->leaderboard_tracker && g_onLeaderboardTrackerHide) {
                env->CallVoidMethod(callbacksRef, g_onLeaderboardTrackerHide, static_cast<jint>(event->leaderboard_tracker->id));
            }
            break;
        case RC_CLIENT_EVENT_GAME_COMPLETED:
            LOGI("RetroAchievements: todas as conquistas do jogo foram obtidas");
            if (g_onGameCompleted) {
                std::string json = buildGameCompletedJson(client, nullptr);
                jstring jJson = env->NewStringUTF(json.c_str());
                env->CallVoidMethod(callbacksRef, g_onGameCompleted, jJson);
                env->DeleteLocalRef(jJson);
            }
            break;
        case RC_CLIENT_EVENT_SUBSET_COMPLETED:
            LOGI("RetroAchievements: todas as conquistas de um subconjunto (DLC) foram obtidas");
            if (event->subset && g_onGameCompleted) {
                std::string json = buildGameCompletedJson(client, event->subset);
                jstring jJson = env->NewStringUTF(json.c_str());
                env->CallVoidMethod(callbacksRef, g_onGameCompleted, jJson);
                env->DeleteLocalRef(jJson);
            }
            break;
        default:
            break;
    }
}

void Achievements::eventHandlerTrampoline(const rc_client_event_t* event, rc_client_t* client) {
    auto* self = static_cast<Achievements*>(rc_client_get_userdata(client));
    if (!self || !g_jvm) return;
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return;
    self->handleEvent(env, event);
}

void Achievements::create() {
    destroyClient();
    client = rc_client_create(&Achievements::peekMemory, &Achievements::serverCallTrampoline);
    rc_client_set_userdata(client, this);
    rc_client_set_event_handler(client, &Achievements::eventHandlerTrampoline);
    rc_client_enable_logging(client, RC_CLIENT_LOG_LEVEL_WARN, [](const char* message, const rc_client_t*) {
        LOGI("rc_client: %s", message);
    });
}

void Achievements::destroyClient() {
    if (client) {
        rc_client_destroy(client);
        client = nullptr;
    }
    if (memoryInitialized) {
        rc_libretro_memory_destroy(&memoryRegions);
        memoryInitialized = false;
    }
    std::lock_guard<std::mutex> lock(pendingCallsMutex);
    pendingCalls.clear();
}

void Achievements::setHardcoreEnabled(bool enabled) {
    if (client) rc_client_set_hardcore_enabled(client, enabled ? 1 : 0);
}

void Achievements::handleLoginResult(JNIEnv* env, int result, const char* errorMessage) {
    if (!callbacksRef || !g_onLoginResult) return;
    jstring message = result != RC_OK && errorMessage ? env->NewStringUTF(errorMessage) : nullptr;
    env->CallVoidMethod(callbacksRef, g_onLoginResult, static_cast<jboolean>(result == RC_OK), message);
    if (message) env->DeleteLocalRef(message);
}

void Achievements::loginCallbackTrampoline(int result, const char* errorMessage, rc_client_t* client, void* userdata) {
    auto* self = static_cast<Achievements*>(userdata);
    if (!self || !g_jvm) return;
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return;
    self->handleLoginResult(env, result, errorMessage);
}

void Achievements::beginLoginWithToken(JNIEnv* env, const std::string& user, const std::string& token) {
    if (!client) return;
    rc_client_begin_login_with_token(client, user.c_str(), token.c_str(), &Achievements::loginCallbackTrampoline, this);
}

void Achievements::handleLoadGameResult(JNIEnv* env, int result, const char* errorMessage) {
    if (!callbacksRef || !g_onLoadGameResult) return;
    jstring message = result != RC_OK && errorMessage ? env->NewStringUTF(errorMessage) : nullptr;
    env->CallVoidMethod(callbacksRef, g_onLoadGameResult, static_cast<jboolean>(result == RC_OK), message);
    if (message) env->DeleteLocalRef(message);
}

void Achievements::loadGameCallbackTrampoline(int result, const char* errorMessage, rc_client_t* client, void* userdata) {
    auto* self = static_cast<Achievements*>(userdata);
    if (!self || !g_jvm) return;
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return;
    self->handleLoadGameResult(env, result, errorMessage);
}

void Achievements::beginLoadGame(JNIEnv* env, Core* core, uint32_t consoleId, const struct retro_memory_map* mmap, const std::string& hash) {
    if (!client) return;
    g_core = core;

    if (memoryInitialized) {
        rc_libretro_memory_destroy(&memoryRegions);
        memoryInitialized = false;
    }
    int result = rc_libretro_memory_init(&memoryRegions, mmap, getCoreMemoryInfo, consoleId);
    memoryInitialized = (result == 1);
    if (!memoryInitialized) {
        LOGW("Failed to initialize achievement memory mapping, falling back to direct RAM");
    }

    rc_client_begin_load_game(client, hash.c_str(), &Achievements::loadGameCallbackTrampoline, this);
}

void Achievements::unloadGame() {
    if (client) rc_client_unload_game(client);
}

void Achievements::doFrame() {
    if (client && rc_client_is_processing_required(client)) {
        rc_client_do_frame(client);
    }
}

bool Achievements::isGameLoaded() const {
    return client && rc_client_is_game_loaded(client);
}

std::string Achievements::getAchievementListJson() {
    if (!client) return "[]";
    rc_client_achievement_list_t* list = rc_client_create_achievement_list(
        client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE,
        RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE
    );
    if (!list) return "[]";

    std::ostringstream json;
    json << "[";
    bool first = true;
    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const auto& bucket = list->buckets[b];
        for (uint32_t a = 0; a < bucket.num_achievements; a++) {
            const rc_client_achievement_t* ach = bucket.achievements[a];
            if (!ach) continue;
            if (!first) json << ",";
            first = false;

            // Nome do ícone (não a URL): o lado Kotlin (RaBadges.load) já sabe montar o endereço
            // certo (normal/trancado) e cuidar do cache local, do jeito que já funcionava antes.
            bool unlocked = ach->unlocked != RC_CLIENT_ACHIEVEMENT_UNLOCKED_NONE;

            json << "{"
                 << "\"id\":" << ach->id << ","
                 << "\"title\":\"" << jsonEscape(ach->title) << "\","
                 << "\"description\":\"" << jsonEscape(ach->description) << "\","
                 << "\"points\":" << ach->points << ","
                 << "\"badge\":\"" << jsonEscape(ach->badge_name) << "\","
                 << "\"unlocked\":" << (unlocked ? "true" : "false")
                 << "}";
        }
    }
    json << "]";

    rc_client_destroy_achievement_list(list);
    return json.str();
}

std::string Achievements::getSummaryJson() {
    if (!client) return "{\"unlocked\":0,\"total\":0}";
    rc_client_user_game_summary_t summary = {};
    rc_client_get_user_game_summary(client, &summary);
    std::ostringstream json;
    json << "{\"unlocked\":" << summary.num_unlocked_achievements
         << ",\"total\":" << (summary.num_core_achievements) << "}";
    return json.str();
}

size_t Achievements::progressSize() const {
    return client ? rc_client_progress_size(client) : 0;
}

int Achievements::serializeProgress(uint8_t* buffer) {
    if (!client || !buffer) return RC_INVALID_STATE;
    const size_t size = rc_client_progress_size(client);
    if (size == 0) return RC_INVALID_STATE;
    return rc_client_serialize_progress_sized(client, buffer, size);
}

int Achievements::deserializeProgress(const uint8_t* buffer) {
    if (!client) return RC_INVALID_STATE;
    return rc_client_deserialize_progress(client, buffer);
}

void Achievements::reset() {
    if (client) rc_client_reset(client);
}

void Achievements::idle() {
    if (client) rc_client_idle(client);
}

bool Achievements::canPause() const {
    return client && rc_client_can_pause(client, nullptr);
}

std::string Achievements::getUserAgentClause() const {
    if (!client) return "";
    char buffer[128] = {};
    rc_client_get_user_agent_clause(client, buffer, sizeof(buffer));
    return buffer;
}

bool Achievements::isProcessingRequired() const {
    return client && rc_client_is_processing_required(client) != 0;
}

}