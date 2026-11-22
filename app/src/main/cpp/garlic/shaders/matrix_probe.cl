// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal OpenCL 1.2 kernel compiled to SPIR-V by clspv.
// Its purpose is to verify that the Android clvk backend can load and execute a
// precompiled compute kernel before the full Garlic Qwen3.5 shader set is enabled.
__kernel __attribute__((reqd_work_group_size(64, 1, 1)))
void matrix_probe(__global const float* a,
                  __global const float* b,
                  __global float* out,
                  const uint count) {
    const size_t index = get_global_id(0);
    if (index < count) {
        out[index] = a[index] + b[index];
    }
}
