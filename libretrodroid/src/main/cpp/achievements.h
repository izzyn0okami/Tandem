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

class Achievements {
public:
    ~Achievements();
    static void setJavaVM(JavaVM* vm);
    void setCallbacks(JNIEnv* env, jobject callbacks);
    void create();
    void destroyClient();
    void setHardcoreEnabled(bool enabled);
    void beginLoginWithToken(JNIEnv* env, const std::string& user, const std::string& token);
    void beginLoadGame(JNIEnv* env, Core* core, uint32_t consoleId, const struct retro_memory_map* mmap, const std::string& hash);
    void unloadGame();
    void doFrame();
    bool isGameLoaded() const;
    uint32_t getGameId() const;
    void deliverServerResponse(JNIEnv* env, int32_t requestId, int httpStatus, const std::string& body, bool networkError);
    std::string getAchievementListJson();
    std::string getSummaryJson();
    uint32_t progressSize() const;
    int serializeProgress(uint8_t* buffer);
    int deserializeProgress(const uint8_t* buffer);
    std::vector<uint8_t> serializeProgress();
    bool deserializeProgress(const uint8_t* data, size_t size);
    void reset();
    void idle();
    bool canPause() const;

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
#endif
