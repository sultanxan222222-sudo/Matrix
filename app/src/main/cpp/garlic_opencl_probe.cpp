#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "MATRIX-GARLIC"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using cl_int = int32_t;
using cl_uint = uint32_t;
using cl_ulong = uint64_t;
using cl_bool = cl_uint;
using cl_bitfield = cl_ulong;
using cl_device_type = cl_bitfield;
using cl_platform_info = cl_uint;
using cl_device_info = cl_uint;
using cl_program_build_info = cl_uint;
using cl_mem_flags = cl_bitfield;
using cl_command_queue_properties = cl_bitfield;
using cl_context_properties = intptr_t;
using cl_queue_properties = intptr_t;

struct _cl_platform_id;
struct _cl_device_id;
struct _cl_context;
struct _cl_command_queue;
struct _cl_program;
struct _cl_kernel;
struct _cl_mem;
using cl_platform_id = _cl_platform_id*;
using cl_device_id = _cl_device_id*;
using cl_context = _cl_context*;
using cl_command_queue = _cl_command_queue*;
using cl_program = _cl_program*;
using cl_kernel = _cl_kernel*;
using cl_mem = _cl_mem*;

constexpr cl_int CL_SUCCESS = 0;
constexpr cl_int CL_DEVICE_NOT_FOUND = -1;
constexpr cl_int CL_BUILD_PROGRAM_FAILURE = -11;
constexpr cl_bool CL_TRUE = 1;
constexpr cl_device_type CL_DEVICE_TYPE_GPU = (1ull << 2);
constexpr cl_device_type CL_DEVICE_TYPE_ALL = 0xFFFFFFFFull;
constexpr cl_platform_info CL_PLATFORM_NAME = 0x0902;
constexpr cl_platform_info CL_PLATFORM_VENDOR = 0x0903;
constexpr cl_platform_info CL_PLATFORM_VERSION = 0x0901;
constexpr cl_device_info CL_DEVICE_TYPE = 0x1000;
constexpr cl_device_info CL_DEVICE_MAX_COMPUTE_UNITS = 0x1002;
constexpr cl_device_info CL_DEVICE_MAX_WORK_GROUP_SIZE = 0x1004;
constexpr cl_device_info CL_DEVICE_GLOBAL_MEM_SIZE = 0x101F;
constexpr cl_device_info CL_DEVICE_LOCAL_MEM_SIZE = 0x1023;
constexpr cl_device_info CL_DEVICE_NAME = 0x102B;
constexpr cl_device_info CL_DEVICE_VENDOR = 0x102C;
constexpr cl_device_info CL_DRIVER_VERSION = 0x102D;
constexpr cl_device_info CL_DEVICE_VERSION = 0x102F;
constexpr cl_device_info CL_PROGRAM_BUILD_LOG = 0x1183;
constexpr cl_mem_flags CL_MEM_WRITE_ONLY = (1ull << 1);
constexpr cl_mem_flags CL_MEM_READ_ONLY = (1ull << 2);
constexpr cl_mem_flags CL_MEM_COPY_HOST_PTR = (1ull << 5);

template <typename T>
T symbol(void* library, const char* name) {
    return reinterpret_cast<T>(dlsym(library, name));
}

using PFN_clGetPlatformIDs = cl_int (*)(cl_uint, cl_platform_id*, cl_uint*);
using PFN_clGetPlatformInfo = cl_int (*)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);
using PFN_clGetDeviceIDs = cl_int (*)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
using PFN_clGetDeviceInfo = cl_int (*)(cl_device_id, cl_device_info, size_t, void*, size_t*);
using PFN_clCreateContext = cl_context (*)(const cl_context_properties*, cl_uint, const cl_device_id*,
                                            void (*)(const char*, const void*, size_t, void*), void*, cl_int*);
using PFN_clCreateCommandQueue = cl_command_queue (*)(cl_context, cl_device_id,
                                                       cl_command_queue_properties, cl_int*);
using PFN_clCreateProgramWithIL = cl_program (*)(cl_context, const void*, size_t, cl_int*);
using PFN_clBuildProgram = cl_int (*)(cl_program, cl_uint, const cl_device_id*, const char*,
                                      void (*)(cl_program, void*), void*);
using PFN_clGetProgramBuildInfo = cl_int (*)(cl_program, cl_device_id, cl_program_build_info,
                                             size_t, void*, size_t*);
using PFN_clCreateKernel = cl_kernel (*)(cl_program, const char*, cl_int*);
using PFN_clCreateBuffer = cl_mem (*)(cl_context, cl_mem_flags, size_t, void*, cl_int*);
using PFN_clSetKernelArg = cl_int (*)(cl_kernel, cl_uint, size_t, const void*);
using PFN_clEnqueueNDRangeKernel = cl_int (*)(cl_command_queue, cl_kernel, cl_uint, const size_t*,
                                               const size_t*, const size_t*, cl_uint, const void*, void*);
using PFN_clEnqueueReadBuffer = cl_int (*)(cl_command_queue, cl_mem, cl_bool, size_t, size_t,
                                           void*, cl_uint, const void*, void*);
using PFN_clFinish = cl_int (*)(cl_command_queue);
using PFN_clReleaseMemObject = cl_int (*)(cl_mem);
using PFN_clReleaseKernel = cl_int (*)(cl_kernel);
using PFN_clReleaseProgram = cl_int (*)(cl_program);
using PFN_clReleaseCommandQueue = cl_int (*)(cl_command_queue);
using PFN_clReleaseContext = cl_int (*)(cl_context);

struct OpenClApi {
    void* library = nullptr;
    PFN_clGetPlatformIDs getPlatformIDs = nullptr;
    PFN_clGetPlatformInfo getPlatformInfo = nullptr;
    PFN_clGetDeviceIDs getDeviceIDs = nullptr;
    PFN_clGetDeviceInfo getDeviceInfo = nullptr;
    PFN_clCreateContext createContext = nullptr;
    PFN_clCreateCommandQueue createCommandQueue = nullptr;
    PFN_clCreateProgramWithIL createProgramWithIL = nullptr;
    PFN_clBuildProgram buildProgram = nullptr;
    PFN_clGetProgramBuildInfo getProgramBuildInfo = nullptr;
    PFN_clCreateKernel createKernel = nullptr;
    PFN_clCreateBuffer createBuffer = nullptr;
    PFN_clSetKernelArg setKernelArg = nullptr;
    PFN_clEnqueueNDRangeKernel enqueueNDRangeKernel = nullptr;
    PFN_clEnqueueReadBuffer enqueueReadBuffer = nullptr;
    PFN_clFinish finish = nullptr;
    PFN_clReleaseMemObject releaseMemObject = nullptr;
    PFN_clReleaseKernel releaseKernel = nullptr;
    PFN_clReleaseProgram releaseProgram = nullptr;
    PFN_clReleaseCommandQueue releaseCommandQueue = nullptr;
    PFN_clReleaseContext releaseContext = nullptr;

    ~OpenClApi() {
        if (library) dlclose(library);
    }

    bool load(std::string* error) {
        // Kotlin loads the packaged libOpenCL.so first; dlopen obtains its API table.
        library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            const char* why = dlerror();
            *error = std::string("Не удалось загрузить встроенный clvk: ") +
                     (why ? why : "libOpenCL.so не найден");
            return false;
        }
#define LOAD(member, name) member = symbol<decltype(member)>(library, name); \
        if (!member) { *error = std::string("В OpenCL runtime отсутствует функция ") + name; return false; }
        LOAD(getPlatformIDs, "clGetPlatformIDs")
        LOAD(getPlatformInfo, "clGetPlatformInfo")
        LOAD(getDeviceIDs, "clGetDeviceIDs")
        LOAD(getDeviceInfo, "clGetDeviceInfo")
        LOAD(createContext, "clCreateContext")
        LOAD(createCommandQueue, "clCreateCommandQueue")
        createProgramWithIL = symbol<PFN_clCreateProgramWithIL>(library, "clCreateProgramWithIL");
        if (!createProgramWithIL) {
            createProgramWithIL = symbol<PFN_clCreateProgramWithIL>(library, "clCreateProgramWithILKHR");
        }
        if (!createProgramWithIL) {
            *error = "OpenCL runtime не поддерживает загрузку предварительно скомпилированного SPIR-V.";
            return false;
        }
        LOAD(buildProgram, "clBuildProgram")
        LOAD(getProgramBuildInfo, "clGetProgramBuildInfo")
        LOAD(createKernel, "clCreateKernel")
        LOAD(createBuffer, "clCreateBuffer")
        LOAD(setKernelArg, "clSetKernelArg")
        LOAD(enqueueNDRangeKernel, "clEnqueueNDRangeKernel")
        LOAD(enqueueReadBuffer, "clEnqueueReadBuffer")
        LOAD(finish, "clFinish")
        LOAD(releaseMemObject, "clReleaseMemObject")
        LOAD(releaseKernel, "clReleaseKernel")
        LOAD(releaseProgram, "clReleaseProgram")
        LOAD(releaseCommandQueue, "clReleaseCommandQueue")
        LOAD(releaseContext, "clReleaseContext")
#undef LOAD
        return true;
    }
};

template <typename Handle, typename Param>
std::string queryString(Handle handle, Param param,
                        cl_int (*query)(Handle, Param, size_t, void*, size_t*)) {
    size_t size = 0;
    if (query(handle, param, 0, nullptr, &size) != CL_SUCCESS || size == 0 || size > 8192) {
        return {};
    }
    std::vector<char> bytes(size + 1, '\0');
    if (query(handle, param, size, bytes.data(), nullptr) != CL_SUCCESS) return {};
    return std::string(bytes.data());
}

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) out << '?';
                else out << static_cast<char>(c);
        }
    }
    return out.str();
}

std::string failure(const std::string& message, cl_int code = 0) {
    std::ostringstream out;
    out << "{\"ready\":false,\"message\":\"" << jsonEscape(message) << "\"";
    if (code != 0) out << ",\"opencl_error\":" << code;
    out << "}";
    return out.str();
}

std::string runProbe(AAssetManager* assets) {
    OpenClApi cl;
    std::string error;
    if (!cl.load(&error)) return failure(error);

    cl_uint platformCount = 0;
    cl_int status = cl.getPlatformIDs(0, nullptr, &platformCount);
    if (status != CL_SUCCESS || platformCount == 0) {
        return failure("OpenCL не обнаружил вычислительную платформу Vulkan.", status);
    }
    std::vector<cl_platform_id> platforms(platformCount);
    status = cl.getPlatformIDs(platformCount, platforms.data(), nullptr);
    if (status != CL_SUCCESS) return failure("Не удалось перечислить OpenCL-платформы.", status);

    cl_platform_id selectedPlatform = nullptr;
    cl_device_id selectedDevice = nullptr;
    for (cl_platform_id platform : platforms) {
        cl_uint count = 0;
        cl_int found = cl.getDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &count);
        if (found != CL_SUCCESS || count == 0) continue;
        std::vector<cl_device_id> devices(count);
        found = cl.getDeviceIDs(platform, CL_DEVICE_TYPE_GPU, count, devices.data(), nullptr);
        if (found == CL_SUCCESS && !devices.empty()) {
            selectedPlatform = platform;
            selectedDevice = devices.front();
            break;
        }
    }
    if (!selectedDevice) {
        return failure("Vulkan/OpenCL runtime загрузился, но GPU-устройство не найдено. CPU здесь не выдаётся за GPU.");
    }

    const std::string platformName = queryString(selectedPlatform, CL_PLATFORM_NAME, cl.getPlatformInfo);
    const std::string platformVendor = queryString(selectedPlatform, CL_PLATFORM_VENDOR, cl.getPlatformInfo);
    const std::string deviceName = queryString(selectedDevice, CL_DEVICE_NAME, cl.getDeviceInfo);
    const std::string deviceVendor = queryString(selectedDevice, CL_DEVICE_VENDOR, cl.getDeviceInfo);
    const std::string deviceVersion = queryString(selectedDevice, CL_DEVICE_VERSION, cl.getDeviceInfo);
    const std::string driverVersion = queryString(selectedDevice, CL_DRIVER_VERSION, cl.getDeviceInfo);

    cl_uint computeUnits = 0;
    size_t maxWorkGroup = 0;
    cl_ulong globalMemory = 0;
    cl_ulong localMemory = 0;
    cl.getDeviceInfo(selectedDevice, CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(computeUnits), &computeUnits, nullptr);
    cl.getDeviceInfo(selectedDevice, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(maxWorkGroup), &maxWorkGroup, nullptr);
    cl.getDeviceInfo(selectedDevice, CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(globalMemory), &globalMemory, nullptr);
    cl.getDeviceInfo(selectedDevice, CL_DEVICE_LOCAL_MEM_SIZE, sizeof(localMemory), &localMemory, nullptr);

    AAsset* asset = AAssetManager_open(assets, "garlic/matrix_probe.spv", AASSET_MODE_BUFFER);
    if (!asset) return failure("Не найден подготовленный SPIR-V smoke-test. Сначала соберите Android-ядра Garlic.");
    const off_t assetLength = AAsset_getLength(asset);
    if (assetLength <= 0 || assetLength > (4 * 1024 * 1024)) {
        AAsset_close(asset);
        return failure("Размер SPIR-V smoke-test недопустим.");
    }
    std::vector<uint8_t> il(static_cast<size_t>(assetLength));
    const int bytesRead = AAsset_read(asset, il.data(), il.size());
    AAsset_close(asset);
    if (bytesRead != assetLength) return failure("Не удалось полностью прочитать SPIR-V smoke-test.");

    cl_context context = cl.createContext(nullptr, 1, &selectedDevice, nullptr, nullptr, &status);
    if (!context || status != CL_SUCCESS) return failure("Не удалось создать OpenCL-контекст Vulkan GPU.", status);
    cl_command_queue queue = cl.createCommandQueue(context, selectedDevice, 0, &status);
    if (!queue || status != CL_SUCCESS) {
        cl.releaseContext(context);
        return failure("Не удалось создать командную очередь GPU.", status);
    }

    constexpr cl_uint count = 64;
    std::array<float, count> left{};
    std::array<float, count> right{};
    std::array<float, count> result{};
    for (cl_uint i = 0; i < count; ++i) {
        left[i] = static_cast<float>(i) * 0.25f;
        right[i] = static_cast<float>(count - i) * 0.5f;
    }

    cl_mem a = nullptr, b = nullptr, c = nullptr;
    cl_program program = nullptr;
    cl_kernel kernel = nullptr;
    auto cleanup = [&]() {
        if (kernel) cl.releaseKernel(kernel);
        if (program) cl.releaseProgram(program);
        if (a) cl.releaseMemObject(a);
        if (b) cl.releaseMemObject(b);
        if (c) cl.releaseMemObject(c);
        cl.finish(queue);
        cl.releaseCommandQueue(queue);
        cl.releaseContext(context);
    };

    program = cl.createProgramWithIL(context, il.data(), il.size(), &status);
    if (!program || status != CL_SUCCESS) {
        cleanup();
        return failure("GPU не принял SPIR-V программу Garlic probe.", status);
    }
    status = cl.buildProgram(program, 1, &selectedDevice, "", nullptr, nullptr);
    if (status != CL_SUCCESS) {
        size_t logSize = 0;
        cl.getProgramBuildInfo(program, selectedDevice, CL_PROGRAM_BUILD_LOG, 0, nullptr, &logSize);
        std::vector<char> log(std::min<size_t>(logSize, 8192) + 1, '\0');
        if (logSize) cl.getProgramBuildInfo(program, selectedDevice, CL_PROGRAM_BUILD_LOG,
                                            log.size() - 1, log.data(), nullptr);
        const std::string message = "SPIR-V kernel build failed: " + std::string(log.data());
        cleanup();
        return failure(message, status);
    }
    kernel = cl.createKernel(program, "matrix_probe", &status);
    if (!kernel || status != CL_SUCCESS) {
        cleanup();
        return failure("В SPIR-V программе не найден kernel matrix_probe.", status);
    }

    a = cl.createBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                        sizeof(left), left.data(), &status);
    if (!a || status != CL_SUCCESS) { cleanup(); return failure("Не удалось выделить входной GPU-буфер A.", status); }
    b = cl.createBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                        sizeof(right), right.data(), &status);
    if (!b || status != CL_SUCCESS) { cleanup(); return failure("Не удалось выделить входной GPU-буфер B.", status); }
    c = cl.createBuffer(context, CL_MEM_WRITE_ONLY, sizeof(result), nullptr, &status);
    if (!c || status != CL_SUCCESS) { cleanup(); return failure("Не удалось выделить выходной GPU-буфер.", status); }

    status = cl.setKernelArg(kernel, 0, sizeof(a), &a);
    if (status == CL_SUCCESS) status = cl.setKernelArg(kernel, 1, sizeof(b), &b);
    if (status == CL_SUCCESS) status = cl.setKernelArg(kernel, 2, sizeof(c), &c);
    if (status == CL_SUCCESS) status = cl.setKernelArg(kernel, 3, sizeof(count), &count);
    if (status != CL_SUCCESS) { cleanup(); return failure("Не удалось передать аргументы GPU-ядру.", status); }

    const size_t globalSize = count;
    const size_t localSize = 64;
    status = cl.enqueueNDRangeKernel(queue, kernel, 1, nullptr, &globalSize, &localSize,
                                     0, nullptr, nullptr);
    if (status == CL_SUCCESS) {
        status = cl.enqueueReadBuffer(queue, c, CL_TRUE, 0, sizeof(result), result.data(),
                                      0, nullptr, nullptr);
    }
    if (status == CL_SUCCESS) status = cl.finish(queue);
    if (status != CL_SUCCESS) { cleanup(); return failure("Выполнение Vulkan/OpenCL GPU-ядра завершилось ошибкой.", status); }

    bool correct = true;
    for (cl_uint i = 0; i < count; ++i) {
        const float expected = left[i] + right[i];
        if (!std::isfinite(result[i]) || std::fabs(result[i] - expected) > 0.0001f) {
            correct = false;
            break;
        }
    }
    cleanup();
    if (!correct) return failure("GPU-ядро выполнилось, но контрольные вычисления не совпали.");

    std::ostringstream out;
    out << "{\"ready\":true,\"probe\":\"passed\","
        << "\"platform\":\"" << jsonEscape(platformName) << "\","
        << "\"platform_vendor\":\"" << jsonEscape(platformVendor) << "\","
        << "\"device\":\"" << jsonEscape(deviceName) << "\","
        << "\"device_vendor\":\"" << jsonEscape(deviceVendor) << "\","
        << "\"device_version\":\"" << jsonEscape(deviceVersion) << "\","
        << "\"driver_version\":\"" << jsonEscape(driverVersion) << "\","
        << "\"compute_units\":" << computeUnits << ","
        << "\"max_work_group\":" << maxWorkGroup << ","
        << "\"global_memory_bytes\":" << globalMemory << ","
        << "\"local_memory_bytes\":" << localMemory << "}";
    return out.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_matrixvpn_ai_GarlicAndroidBackend_nativeProbe(JNIEnv* env, jobject,
                                                        jobject javaAssetManager) {
    AAssetManager* assets = AAssetManager_fromJava(env, javaAssetManager);
    if (!assets) {
        const std::string result = failure("Android не передал AssetManager.");
        return env->NewStringUTF(result.c_str());
    }
    const std::string result = runProbe(assets);
    return env->NewStringUTF(result.c_str());
}
