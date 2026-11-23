#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MatrixClRuntime MatrixClRuntime;
typedef void* MatrixClBuffer;
typedef void* MatrixClKernel;

/*
 * Create a clvk/OpenCL runtime around an already compiled SPIR-V module.
 * The runtime owns its OpenCL context, in-order command queue, program and cached
 * kernel/sub-buffer handles. It does not own the caller's host memory pool.
 */
MatrixClRuntime* matrix_cl_create(const void* spirv, size_t spirv_size,
                                  char* error, size_t error_capacity);
void matrix_cl_destroy(MatrixClRuntime* runtime);

MatrixClBuffer matrix_cl_create_buffer(MatrixClRuntime* runtime, size_t size,
                                        const void* initial_data,
                                        char* error, size_t error_capacity);
int matrix_cl_register_region(MatrixClRuntime* runtime, uintptr_t host_address,
                              size_t size, const void* initial_data,
                              char* error, size_t error_capacity);
MatrixClBuffer matrix_cl_lookup_region(MatrixClRuntime* runtime, uintptr_t host_address,
                                       size_t* bytes_available,
                                       char* error, size_t error_capacity);
int matrix_cl_write_address(MatrixClRuntime* runtime, uintptr_t host_address,
                            size_t size, const void* source);
int matrix_cl_read_address(MatrixClRuntime* runtime, uintptr_t host_address,
                           size_t size, void* destination);
MatrixClBuffer matrix_cl_subbuffer(MatrixClRuntime* runtime, MatrixClBuffer parent,
                                    size_t origin, size_t size,
                                    char* error, size_t error_capacity);

int matrix_cl_write(MatrixClRuntime* runtime, MatrixClBuffer buffer,
                    size_t offset, size_t size, const void* source);
int matrix_cl_read(MatrixClRuntime* runtime, MatrixClBuffer buffer,
                   size_t offset, size_t size, void* destination);

MatrixClKernel matrix_cl_get_kernel(MatrixClRuntime* runtime, const char* name,
                                    char* error, size_t error_capacity);
int matrix_cl_set_mem_arg(MatrixClRuntime* runtime, MatrixClKernel kernel,
                          uint32_t index, MatrixClBuffer buffer);
int matrix_cl_set_arg(MatrixClRuntime* runtime, MatrixClKernel kernel,
                      uint32_t index, size_t size, const void* value);
int matrix_cl_enqueue(MatrixClRuntime* runtime, MatrixClKernel kernel,
                      size_t groups, size_t local_size);
int matrix_cl_finish(MatrixClRuntime* runtime);

size_t matrix_cl_max_workgroup(MatrixClRuntime* runtime);
size_t matrix_cl_base_address_alignment(MatrixClRuntime* runtime);
uint64_t matrix_cl_global_memory(MatrixClRuntime* runtime);
uint64_t matrix_cl_max_allocation(MatrixClRuntime* runtime);

#ifdef __cplusplus
}
#endif
