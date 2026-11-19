/*
 * MATRIX private policy runtime over Garlic LLM.
 * Derived from Garlic LLM; GPL-3.0-or-later.
 * No chat, voice, network client or general-purpose tool execution is exposed.
 */

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include "engine.h"
#include "tokenizer.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

#define MATRIX_AGENT_TAG "MATRIX-GARLIC"

namespace {

constexpr int MAX_PROMPT_TOKENS = 1024;
constexpr int MAX_OUTPUT_TOKENS = 80;
constexpr int MAX_MODEL_CONTEXT = 8192;

struct MatrixAgent {
    Engine engine{};
    Tokenizer tokenizer{};
    std::vector<uint8_t> tokenizerBytes;
    bool engineReady = false;
    bool tokenizerReady = false;
    std::mutex lock;
};

void logError(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, MATRIX_AGENT_TAG, "%s", message ? message : "unknown error");
}

std::string jstringToString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool ensureDirectory(const std::string& path) {
    if (path.empty()) return false;
    if (mkdir(path.c_str(), 0700) == 0 || errno == EEXIST) {
        struct stat info{};
        return stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
    }
    return false;
}

bool copyAsset(AAssetManager* manager, const char* assetName, const std::string& destination,
               std::string* error) {
    AAsset* asset = AAssetManager_open(manager, assetName, AASSET_MODE_STREAMING);
    if (!asset) {
        if (error) *error = std::string("Missing packaged asset: ") + assetName;
        return false;
    }

    const std::string temporary = destination + ".part";
    FILE* output = fopen(temporary.c_str(), "wb");
    if (!output) {
        AAsset_close(asset);
        if (error) *error = std::string("Cannot create ") + temporary;
        return false;
    }

    std::array<uint8_t, 64 * 1024> buffer{};
    bool ok = true;
    for (;;) {
        const int count = AAsset_read(asset, buffer.data(), buffer.size());
        if (count < 0) {
            ok = false;
            break;
        }
        if (count == 0) break;
        if (fwrite(buffer.data(), 1, static_cast<size_t>(count), output) != static_cast<size_t>(count)) {
            ok = false;
            break;
        }
    }
    if (fflush(output) != 0) ok = false;
    if (fclose(output) != 0) ok = false;
    AAsset_close(asset);

    if (!ok || rename(temporary.c_str(), destination.c_str()) != 0) {
        unlink(temporary.c_str());
        if (error) *error = std::string("Could not install asset: ") + assetName;
        return false;
    }
    return true;
}

bool readFile(const std::string& path, std::vector<uint8_t>* bytes, std::string* error) {
    FILE* file = fopen(path.c_str(), "rb");
    if (!file) {
        if (error) *error = std::string("Cannot open ") + path;
        return false;
    }
    if (fseeko(file, 0, SEEK_END) != 0) {
        fclose(file);
        if (error) *error = "Cannot seek model support file.";
        return false;
    }
    const off_t size = ftello(file);
    if (size <= 0 || size > (256ll << 20) || fseeko(file, 0, SEEK_SET) != 0) {
        fclose(file);
        if (error) *error = "Model support file has an invalid size.";
        return false;
    }
    bytes->resize(static_cast<size_t>(size));
    const size_t readCount = fread(bytes->data(), 1, bytes->size(), file);
    fclose(file);
    if (readCount != bytes->size()) {
        bytes->clear();
        if (error) *error = "Model support file is truncated.";
        return false;
    }
    return true;
}

void freeTokenizer(Tokenizer* tokenizer) {
    if (!tokenizer) return;
    free(tokenizer->raw);
    free(tokenizer->raw_len);
    free(tokenizer->special);
    free(tokenizer->keys);
    free(tokenizer->rank);
    free(tokenizer->merged);
    memset(tokenizer, 0, sizeof(*tokenizer));
}

void engineProgress(void*, float fraction, const char* what) {
    __android_log_print(ANDROID_LOG_INFO, MATRIX_AGENT_TAG, "model %.0f%%: %s",
                        std::max(0.0f, std::min(100.0f, fraction * 100.0f)),
                        what ? what : "loading");
}

std::string decodeToken(const Tokenizer& tokenizer, uint32_t id) {
    const uint8_t* piece = nullptr;
    const int size = tok_piece(&tokenizer, id, &piece);
    if (size <= 0 || !piece) return {};
    return std::string(reinterpret_cast<const char*>(piece), static_cast<size_t>(size));
}

std::string evaluateLocked(MatrixAgent* agent, const std::string& observation) {
    if (!agent || !agent->engineReady || !agent->tokenizerReady) {
        return "{\"error\":\"MATRIX_GARLIC_NOT_READY\"}";
    }

    // Only aggregate metrics and opaque IDs reach the model. Keys, peer secrets, raw config,
    // IP addresses, DNS contents and browsing data are intentionally excluded.
    static const char* systemText =
        "You are the internal network policy engine of MATRIX VPN. "
        "You are not a chat assistant. Analyse the JSON network observation and return exactly one "
        "JSON object with fields action, profile_id, confidence, reason_code. "
        "action must be KEEP_CURRENT, PROBE_PROFILE, or PROPOSE_FAILOVER. "
        "KEEP_CURRENT requires profile_id null. Other actions must use exactly one ID from candidate_profile_ids. "
        "Never invent an ID. If evidence is insufficient, use KEEP_CURRENT. "
        "Never request secrets or change a kill switch, firewall, DNS security, or tunnel protection. "
        "Output no Markdown and no explanation outside the JSON object.\n";

    std::vector<uint32_t> prompt;
    prompt.reserve(MAX_PROMPT_TOKENS);
    auto appendText = [&](const std::string& text) -> bool {
        if (prompt.size() >= MAX_PROMPT_TOKENS) return false;
        const int remaining = MAX_PROMPT_TOKENS - static_cast<int>(prompt.size());
        std::vector<uint32_t> encoded(static_cast<size_t>(remaining));
        const int count = tok_encode(&agent->tokenizer, text.c_str(), encoded.data(), remaining);
        if (count < 0 || count > remaining) return false;
        prompt.insert(prompt.end(), encoded.begin(), encoded.begin() + count);
        return true;
    };

    prompt.push_back(agent->engine.im_start);
    if (!appendText("system\n") || !appendText(systemText)) {
        return "{\"error\":\"MATRIX_PROMPT_TOO_LONG\"}";
    }
    prompt.push_back(agent->engine.im_end);
    prompt.push_back(agent->engine.im_start);
    if (!appendText("user\n") || !appendText(observation)) {
        return "{\"error\":\"MATRIX_PROMPT_TOO_LONG\"}";
    }
    prompt.push_back(agent->engine.im_end);
    prompt.push_back(agent->engine.im_start);
    if (!appendText("assistant\n") || prompt.empty() || prompt.size() > MAX_MODEL_CONTEXT - MAX_OUTPUT_TOKENS) {
        return "{\"error\":\"MATRIX_PROMPT_TOO_LONG\"}";
    }

    engine_reset(&agent->engine);
    uint32_t next = 0;
    const uint32_t seed = 0x4D415452u;
    if (engine_run(&agent->engine, prompt.data(), static_cast<int>(prompt.size()), 0, 0.0f, seed, &next) != 0) {
        return "{\"error\":\"MATRIX_GARLIC_INFERENCE_FAILED\"}";
    }

    std::string response;
    response.reserve(512);
    int position = static_cast<int>(prompt.size());
    for (int generated = 0; generated < MAX_OUTPUT_TOKENS; ++generated) {
        if (next == agent->engine.im_end || next == agent->engine.eos || next == agent->engine.eos2) break;
        response += decodeToken(agent->tokenizer, next);
        uint32_t following = 0;
        if (engine_run(&agent->engine, &next, 1, position, 0.0f,
                       seed + static_cast<uint32_t>(generated + 1), &following) != 0) {
            return "{\"error\":\"MATRIX_GARLIC_INFERENCE_FAILED\"}";
        }
        next = following;
        ++position;
        if (position >= static_cast<int>(agent->engine.max_seq) - 1) break;
    }

    if (response.empty()) return "{\"error\":\"MATRIX_GARLIC_EMPTY_RESPONSE\"}";
    return response;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_matrixvpn_ai_GarlicMatrixPolicy_nativeCreate(JNIEnv* env, jobject,
                                                       jobject javaAssetManager,
                                                       jstring modelDirectory) {
    AAssetManager* assets = AAssetManager_fromJava(env, javaAssetManager);
    const std::string directory = jstringToString(env, modelDirectory);
    if (!assets || directory.empty() || !ensureDirectory(directory)) {
        logError("Invalid model directory or Android AssetManager.");
        return 0;
    }

    auto* agent = new (std::nothrow) MatrixAgent();
    if (!agent) {
        logError("Not enough memory to create MATRIX Garlic controller.");
        return 0;
    }

    std::string error;
    const std::string shaderPath = directory + "/qwen35.spv";
    const std::string tokenizerPath = directory + "/tokenizer.bin";
    if (access(shaderPath.c_str(), R_OK) != 0 &&
        !copyAsset(assets, "garlic/qwen35.spv", shaderPath, &error)) {
        logError(error.c_str());
        delete agent;
        return 0;
    }
    if (access(tokenizerPath.c_str(), R_OK) != 0 &&
        !copyAsset(assets, "garlic/tokenizer.bin", tokenizerPath, &error)) {
        logError(error.c_str());
        delete agent;
        return 0;
    }

    if (!readFile(tokenizerPath, &agent->tokenizerBytes, &error)) {
        logError(error.c_str());
        delete agent;
        return 0;
    }
    const int tokenizerStatus = tok_load(&agent->tokenizer,
                                         agent->tokenizerBytes.data(),
                                         agent->tokenizerBytes.size());
    if (tokenizerStatus != 0) {
        logError("Garlic tokenizer.bin is invalid or was truncated.");
        freeTokenizer(&agent->tokenizer);
        delete agent;
        return 0;
    }
    agent->tokenizerReady = true;

    const std::string modelIndex = directory + "/model.idx";
    if (access(modelIndex.c_str(), R_OK) != 0) {
        logError("The installed model pack is missing model.idx.");
        freeTokenizer(&agent->tokenizer);
        delete agent;
        return 0;
    }

    if (engine_init(&agent->engine, directory.c_str(), engineProgress, nullptr) != 0) {
        logError(agent->engine.error);
        engine_close(&agent->engine);
        freeTokenizer(&agent->tokenizer);
        delete agent;
        return 0;
    }
    agent->engineReady = true;
    __android_log_print(ANDROID_LOG_INFO, MATRIX_AGENT_TAG,
                        "Garlic Qwen3.5 engine initialized: %u layers, %u context, %u vocabulary.",
                        agent->engine.n_layers, agent->engine.max_seq, agent->engine.vocab);
    return reinterpret_cast<jlong>(agent);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_matrixvpn_ai_GarlicMatrixPolicy_nativeEvaluate(JNIEnv* env, jobject, jlong handle,
                                                         jstring observationJson) {
    auto* agent = reinterpret_cast<MatrixAgent*>(handle);
    if (!agent || !observationJson) {
        return env->NewStringUTF("{\"error\":\"MATRIX_GARLIC_NOT_READY\"}");
    }
    const std::string observation = jstringToString(env, observationJson);
    if (observation.empty() || observation.size() > 64 * 1024) {
        return env->NewStringUTF("{\"error\":\"MATRIX_INVALID_OBSERVATION\"}");
    }
    std::lock_guard<std::mutex> guard(agent->lock);
    const std::string result = evaluateLocked(agent, observation);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_matrixvpn_ai_GarlicMatrixPolicy_nativeClose(JNIEnv*, jobject, jlong handle) {
    auto* agent = reinterpret_cast<MatrixAgent*>(handle);
    if (!agent) return;
    {
        std::lock_guard<std::mutex> guard(agent->lock);
        if (agent->engineReady) engine_close(&agent->engine);
        agent->engineReady = false;
        if (agent->tokenizerReady) freeTokenizer(&agent->tokenizer);
        agent->tokenizerReady = false;
    }
    delete agent;
}
