#include "garlic_opencl_runtime.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

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
using cl_buffer_create_type = cl_uint;

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
constexpr cl_device_type CL_DEVICE_TYPE_GPU = (1ull << 2);
constexpr cl_platform_info CL_PLATFORM_NAME = 0x0902;
constexpr cl_device_info CL_DEVICE_MAX_WORK_GROUP_SIZE = 0x1004;
constexpr cl_device_info CL_DEVICE_MAX_MEM_ALLOC_SIZE = 0x1010;
constexpr cl_device_info CL_DEVICE_MEM_BASE_ADDR_ALIGN = 0x1019;
constexpr cl_device_info CL_DEVICE_GLOBAL_MEM_SIZE = 0x101F;
constexpr cl_mem_flags CL_MEM_READ_WRITE = (1ull << 0);
constexpr cl_mem_flags CL_MEM_COPY_HOST_PTR = (1ull << 5);
constexpr cl_buffer_create_type CL_BUFFER_CREATE_TYPE_REGION = 0x1220;
constexpr cl_program_build_info CL_PROGRAM_BUILD_LOG = 0x1183;

template <typename T>
T symbol(void* library, const char* name) {
    return reinterpret_cast<T>(dlsym(library, name));
}

struct Api {
    void* library = nullptr;
#define MATRIX_CL_FN(name, signature) using name##Fn = signature; name##Fn name = nullptr;
    MATRIX_CL_FN(getPlatformIDs, cl_int (*)(cl_uint, cl_platform_id*, cl_uint*))
    MATRIX_CL_FN(getDeviceIDs, cl_int (*)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*))
    MATRIX_CL_FN(getDeviceInfo, cl_int (*)(cl_device_id, cl_device_info, size_t, void*, size_t*))
    MATRIX_CL_FN(createContext, cl_context (*)(const cl_context_properties*, cl_uint, const cl_device_id*,
                                               void (*)(const char*, const void*, size_t, void*), void*, cl_int*))
    MATRIX_CL_FN(createCommandQueue, cl_command_queue (*)(cl_context, cl_device_id,
                                                           cl_command_queue_properties, cl_int*))
    MATRIX_CL_FN(createProgramWithIL, cl_program (*)(cl_context, const void*, size_t, cl_int*))
    MATRIX_CL_FN(buildProgram, cl_int (*)(cl_program, cl_uint, const cl_device_id*, const char*,
                                          void (*)(cl_program, void*), void*))
    MATRIX_CL_FN(getProgramBuildInfo, cl_int (*)(cl_program, cl_device_id, cl_program_build_info,
                                                 size_t, void*, size_t*))
    MATRIX_CL_FN(createKernel, cl_kernel (*)(cl_program, const char*, cl_int*))
    MATRIX_CL_FN(createBuffer, cl_mem (*)(cl_context, cl_mem_flags, size_t, void*, cl_int*))
    MATRIX_CL_FN(createSubBuffer, cl_mem (*)(cl_mem, cl_mem_flags, cl_buffer_create_type,
                                             const void*, cl_int*))
    MATRIX_CL_FN(setKernelArg, cl_int (*)(cl_kernel, cl_uint, size_t, const void*))
    MATRIX_CL_FN(enqueueNDRangeKernel, cl_int (*)(cl_command_queue, cl_kernel, cl_uint, const size_t*,
                                                  const size_t*, const size_t*, cl_uint, const void*, void*))
    MATRIX_CL_FN(enqueueWriteBuffer, cl_int (*)(cl_command_queue, cl_mem, cl_bool, size_t, size_t,
                                                const void*, cl_uint, const void*, void*))
    MATRIX_CL_FN(enqueueReadBuffer, cl_int (*)(cl_command_queue, cl_mem, cl_bool, size_t, size_t,
                                               void*, cl_uint, const void*, void*))
    MATRIX_CL_FN(finish, cl_int (*)(cl_command_queue))
    MATRIX_CL_FN(releaseMemObject, cl_int (*)(cl_mem))
    MATRIX_CL_FN(releaseKernel, cl_int (*)(cl_kernel))
    MATRIX_CL_FN(releaseProgram, cl_int (*)(cl_program))
    MATRIX_CL_FN(releaseCommandQueue, cl_int (*)(cl_command_queue))
    MATRIX_CL_FN(releaseContext, cl_int (*)(cl_context))
#undef MATRIX_CL_FN

    ~Api() {
        if (library) dlclose(library);
    }

    bool load(std::string& error) {
        library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            const char* why = dlerror();
            error = std::string("Cannot load clvk OpenCL runtime: ") + (why ? why : "not found");
            return false;
        }
#define LOAD(member, exportName) member = symbol<decltype(member)>(library, exportName); \
    if (!member) { error = std::string("Missing OpenCL symbol: ") + exportName; return false; }
        LOAD(getPlatformIDs, "clGetPlatformIDs")
        LOAD(getDeviceIDs, "clGetDeviceIDs")
        LOAD(getDeviceInfo, "clGetDeviceInfo")
        LOAD(createContext, "clCreateContext")
        LOAD(createCommandQueue, "clCreateCommandQueue")
        createProgramWithIL = symbol<decltype(createProgramWithIL)>(library, "clCreateProgramWithIL");
        if (!createProgramWithIL)
            createProgramWithIL = symbol<decltype(createProgramWithIL)>(library, "clCreateProgramWithILKHR");
        if (!createProgramWithIL) {
            error = "OpenCL runtime has no clCreateProgramWithIL entry point.";
            return false;
        }
        LOAD(buildProgram, "clBuildProgram")
        LOAD(getProgramBuildInfo, "clGetProgramBuildInfo")
        LOAD(createKernel, "clCreateKernel")
        LOAD(createBuffer, "clCreateBuffer")
        LOAD(createSubBuffer, "clCreateSubBuffer")
        LOAD(setKernelArg, "clSetKernelArg")
        LOAD(enqueueNDRangeKernel, "clEnqueueNDRangeKernel")
        LOAD(enqueueWriteBuffer, "clEnqueueWriteBuffer")
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

std::string buildLog(Api& api, cl_program program, cl_device_id device) {
    size_t size = 0;
    if (api.getProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, 0, nullptr, &size) != CL_SUCCESS ||
        size == 0 || size > 64 * 1024) return {};
    std::vector<char> text(size + 1, '\0');
    if (api.getProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, size, text.data(), nullptr) != CL_SUCCESS)
        return {};
    return std::string(text.data());
}

void writeError(char* out, size_t capacity, const std::string& message) {
    if (!out || capacity == 0) return;
    const size_t n = std::min(capacity - 1, message.size());
    std::memcpy(out, message.data(), n);
    out[n] = '\0';
}

} // namespace

struct MatrixClRuntime {
    std::unique_ptr<Api> api;
    cl_platform_id platform = nullptr;
    cl_device_id device = nullptr;
    cl_context context = nullptr;
    cl_command_queue queue = nullptr;
    cl_program program = nullptr;
    size_t maxWorkGroup = 0;
    size_t baseAlignmentBytes = 1;
    uint64_t globalMemory = 0;
    uint64_t maxAllocation = 0;
    std::unordered_map<std::string, cl_kernel> kernels;
    std::unordered_map<std::string, cl_mem> subbuffers;
    std::vector<cl_mem> rootBuffers;

    ~MatrixClRuntime() {
        if (!api) return;
        if (queue) api->finish(queue);
        for (auto& item : kernels) if (item.second) api->releaseKernel(item.second);
        for (auto& item : subbuffers) if (item.second) api->releaseMemObject(item.second);
        for (cl_mem buffer : rootBuffers) if (buffer) api->releaseMemObject(buffer);
        if (program) api->releaseProgram(program);
        if (queue) api->releaseCommandQueue(queue);
        if (context) api->releaseContext(context);
    }
};

extern "C" MatrixClRuntime* matrix_cl_create(const void* spirv, size_t spirv_size,
                                               char* error, size_t error_capacity) {
    auto runtime = std::unique_ptr<MatrixClRuntime>(new (std::nothrow) MatrixClRuntime());
    if (!runtime) {
        writeError(error, error_capacity, "Out of memory creating MATRIX OpenCL runtime.");
        return nullptr;
    }
    runtime->api.reset(new (std::nothrow) Api());
    if (!runtime->api) {
        writeError(error, error_capacity, "Out of memory creating OpenCL function table.");
        return nullptr;
    }
    std::string message;
    if (!runtime->api->load(message)) {
        writeError(error, error_capacity, message);
        return nullptr;
    }
    if (!spirv || spirv_size < 20 || (spirv_size % sizeof(uint32_t)) != 0) {
        writeError(error, error_capacity, "Invalid precompiled SPIR-V module.");
        return nullptr;
    }

    cl_uint count = 0;
    cl_int status = runtime->api->getPlatformIDs(0, nullptr, &count);
    if (status != CL_SUCCESS || count == 0) {
        writeError(error, error_capacity, "No OpenCL platforms exposed by clvk.");
        return nullptr;
    }
    std::vector<cl_platform_id> platforms(count);
    status = runtime->api->getPlatformIDs(count, platforms.data(), nullptr);
    if (status != CL_SUCCESS) {
        writeError(error, error_capacity, "Cannot enumerate OpenCL platforms.");
        return nullptr;
    }

    for (cl_platform_id platform : platforms) {
        cl_uint devicesCount = 0;
        status = runtime->api->getDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &devicesCount);
        if (status != CL_SUCCESS || devicesCount == 0) continue;
        std::vector<cl_device_id> devices(devicesCount);
        status = runtime->api->getDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devicesCount, devices.data(), nullptr);
        if (status == CL_SUCCESS && !devices.empty()) {
            runtime->platform = platform;
            runtime->device = devices.front();
            break;
        }
    }
    if (!runtime->device) {
        writeError(error, error_capacity, "clvk found no Vulkan GPU device.");
        return nullptr;
    }

    cl_uint alignmentBits = 0;
    runtime->api->getDeviceInfo(runtime->device, CL_DEVICE_MAX_WORK_GROUP_SIZE,
                                sizeof(runtime->maxWorkGroup), &runtime->maxWorkGroup, nullptr);
    runtime->api->getDeviceInfo(runtime->device, CL_DEVICE_MEM_BASE_ADDR_ALIGN,
                                sizeof(alignmentBits), &alignmentBits, nullptr);
    runtime->api->getDeviceInfo(runtime->device, CL_DEVICE_GLOBAL_MEM_SIZE,
                                sizeof(runtime->globalMemory), &runtime->globalMemory, nullptr);
    runtime->api->getDeviceInfo(runtime->device, CL_DEVICE_MAX_MEM_ALLOC_SIZE,
                                sizeof(runtime->maxAllocation), &runtime->maxAllocation, nullptr);
    runtime->baseAlignmentBytes = std::max<size_t>(1, (alignmentBits + 7u) / 8u);

    runtime->context = runtime->api->createContext(nullptr, 1, &runtime->device, nullptr, nullptr, &status);
    if (!runtime->context || status != CL_SUCCESS) {
        writeError(error, error_capacity, "Cannot create Vulkan-backed OpenCL context.");
        return nullptr;
    }
    runtime->queue = runtime->api->createCommandQueue(runtime->context, runtime->device, 0, &status);
    if (!runtime->queue || status != CL_SUCCESS) {
        writeError(error, error_capacity, "Cannot create OpenCL command queue.");
        return nullptr;
    }
    runtime->program = runtime->api->createProgramWithIL(runtime->context, spirv, spirv_size, &status);
    if (!runtime->program || status != CL_SUCCESS) {
        writeError(error, error_capacity, "clCreateProgramWithIL rejected Garlic SPIR-V.");
        return nullptr;
    }
    status = runtime->api->buildProgram(runtime->program, 1, &runtime->device, "", nullptr, nullptr);
    if (status != CL_SUCCESS) {
        writeError(error, error_capacity, "Garlic SPIR-V module cannot be loaded: " +
                   buildLog(*runtime->api, runtime->program, runtime->device));
        return nullptr;
    }
    return runtime.release();
}

extern "C" void matrix_cl_destroy(MatrixClRuntime* runtime) {
    delete runtime;
}

extern "C" MatrixClBuffer matrix_cl_create_buffer(MatrixClRuntime* runtime, size_t size,
                                                    const void* initial_data,
                                                    char* error, size_t error_capacity) {
    if (!runtime || !runtime->api || size == 0 || size > runtime->maxAllocation) {
        writeError(error, error_capacity, "Requested Garlic GPU buffer is outside the device allocation limit.");
        return nullptr;
    }
    cl_int status = CL_SUCCESS;
    const cl_mem_flags flags = CL_MEM_READ_WRITE | (initial_data ? CL_MEM_COPY_HOST_PTR : 0);
    cl_mem buffer = runtime->api->createBuffer(runtime->context, flags, size,
                                                const_cast<void*>(initial_data), &status);
    if (!buffer || status != CL_SUCCESS) {
        writeError(error, error_capacity, "Vulkan GPU memory allocation failed (OpenCL error " +
                   std::to_string(status) + ").");
        return nullptr;
    }
    runtime->rootBuffers.push_back(buffer);
    return buffer;
}

extern "C" MatrixClBuffer matrix_cl_subbuffer(MatrixClRuntime* runtime, MatrixClBuffer parent,
                                                size_t origin, size_t size,
                                                char* error, size_t error_capacity) {
    if (!runtime || !runtime->api || !parent || size == 0 ||
        (origin % runtime->baseAlignmentBytes) != 0) {
        writeError(error, error_capacity, "Garlic buffer offset is not aligned for this Vulkan device.");
        return nullptr;
    }
    const std::string key = std::to_string(origin) + ":" + std::to_string(size);
    auto found = runtime->subbuffers.find(key);
    if (found != runtime->subbuffers.end()) return found->second;

    struct Region { size_t origin; size_t size; } region{origin, size};
    cl_int status = CL_SUCCESS;
    cl_mem sub = runtime->api->createSubBuffer(static_cast<cl_mem>(parent), 0,
                                               CL_BUFFER_CREATE_TYPE_REGION, &region, &status);
    if (!sub || status != CL_SUCCESS) {
        writeError(error, error_capacity, "Cannot create OpenCL view of Garlic memory (error " +
                   std::to_string(status) + ").");
        return nullptr;
    }
    runtime->subbuffers.emplace(key, sub);
    return sub;
}

extern "C" int matrix_cl_write(MatrixClRuntime* runtime, MatrixClBuffer buffer,
                                size_t offset, size_t size, const void* source) {
    if (!runtime || !buffer || !source || size == 0) return -1;
    const cl_int status = runtime->api->enqueueWriteBuffer(runtime->queue,
        static_cast<cl_mem>(buffer), 1, offset, size, source, 0, nullptr, nullptr);
    return status == CL_SUCCESS ? 0 : status;
}

extern "C" int matrix_cl_read(MatrixClRuntime* runtime, MatrixClBuffer buffer,
                               size_t offset, size_t size, void* destination) {
    if (!runtime || !buffer || !destination || size == 0) return -1;
    const cl_int status = runtime->api->enqueueReadBuffer(runtime->queue,
        static_cast<cl_mem>(buffer), 1, offset, size, destination, 0, nullptr, nullptr);
    return status == CL_SUCCESS ? 0 : status;
}

extern "C" MatrixClKernel matrix_cl_get_kernel(MatrixClRuntime* runtime, const char* name,
                                                char* error, size_t error_capacity) {
    if (!runtime || !runtime->api || !name || !*name) return nullptr;
    auto found = runtime->kernels.find(name);
    if (found != runtime->kernels.end()) return found->second;
    cl_int status = CL_SUCCESS;
    cl_kernel kernel = runtime->api->createKernel(runtime->program, name, &status);
    if (!kernel || status != CL_SUCCESS) {
        writeError(error, error_capacity, "Garlic SPIR-V does not provide kernel '" +
                   std::string(name) + "' (OpenCL error " + std::to_string(status) + ").");
        return nullptr;
    }
    runtime->kernels.emplace(name, kernel);
    return kernel;
}

extern "C" int matrix_cl_set_mem_arg(MatrixClRuntime* runtime, MatrixClKernel kernel,
                                      uint32_t index, MatrixClBuffer buffer) {
    if (!runtime || !kernel) return -1;
    cl_mem handle = static_cast<cl_mem>(buffer);
    // OpenCL expects arg_value to point to a cl_mem handle even when the handle is NULL.
    return runtime->api->setKernelArg(static_cast<cl_kernel>(kernel), index,
                                      sizeof(handle), &handle);
}

extern "C" int matrix_cl_set_arg(MatrixClRuntime* runtime, MatrixClKernel kernel,
                                  uint32_t index, size_t size, const void* value) {
    if (!runtime || !kernel || size == 0) return -1;
    return runtime->api->setKernelArg(static_cast<cl_kernel>(kernel), index, size, value);
}

extern "C" int matrix_cl_enqueue(MatrixClRuntime* runtime, MatrixClKernel kernel,
                                  size_t groups, size_t local_size) {
    if (!runtime || !kernel || groups == 0 || local_size == 0 ||
        local_size > runtime->maxWorkGroup || groups > SIZE_MAX / local_size) return -1;
    const size_t global_size = groups * local_size;
    const cl_int status = runtime->api->enqueueNDRangeKernel(runtime->queue,
        static_cast<cl_kernel>(kernel), 1, nullptr, &global_size, &local_size,
        0, nullptr, nullptr);
    return status == CL_SUCCESS ? 0 : status;
}

extern "C" int matrix_cl_finish(MatrixClRuntime* runtime) {
    if (!runtime || !runtime->queue) return -1;
    return runtime->api->finish(runtime->queue);
}

extern "C" size_t matrix_cl_max_workgroup(MatrixClRuntime* runtime) {
    return runtime ? runtime->maxWorkGroup : 0;
}

extern "C" size_t matrix_cl_base_address_alignment(MatrixClRuntime* runtime) {
    return runtime ? runtime->baseAlignmentBytes : 0;
}

extern "C" uint64_t matrix_cl_global_memory(MatrixClRuntime* runtime) {
    return runtime ? runtime->globalMemory : 0;
}

extern "C" uint64_t matrix_cl_max_allocation(MatrixClRuntime* runtime) {
    return runtime ? runtime->maxAllocation : 0;
}
