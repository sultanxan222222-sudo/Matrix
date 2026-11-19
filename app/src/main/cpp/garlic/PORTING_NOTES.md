# Garlic LLM → Android: source adaptation status

This directory contains source files adapted from the Garlic LLM Qwen3.5 decoder for an Android OpenCL/Vulkan target.

## Upstream source and licence

- Original project: https://github.com/leonardo-alegria/garlic-llm
- Original shader files: `shaders/common.h`, `shaders/attn.h`, `shaders/attn_tq.h`, `shaders/prefill.h`, `shaders/qwen35.cl`
- Original application-code licence: GPL-3.0-or-later. The complete licence is retained at `third_party/garlic-llm/LICENSE`.
- The original model weights retain their own Qwen licence; the weights are not copied into this source directory.

## Adaptations already applied

1. PS4 work-item/work-group built-ins were replaced with standard OpenCL `get_local_id(0)` and `get_group_id(0)`.
2. GCN-only workgroup fences/barriers were replaced with OpenCL local/global memory barriers.
3. AMD reciprocal-square-root and math intrinsics were mapped to OpenCL C built-ins.
4. AMD-only read-first-lane operations were replaced with the corresponding uniform workgroup lane index. This removes the AMD scalar-load optimization, not the mathematical indexing.
5. No-op inline assembly constraints were removed.
6. Compiler-specific vector and half aliases were changed to OpenCL vector/half types.

## Not yet ported

- The original `chat/engine.c` still uses PS4 GNM/PM4 command buffers and Garlic direct-memory allocation. Those are not compatible with Android and must be replaced by `cl_mem` objects, OpenCL kernel objects, command queues, events, and explicit synchronization.
- The Q4S/Q3S speculative-decoding path in `shaders/spec.h` still contains an AMD `v_mad_i32_i24` instruction. The main Qwen3.5 decoder is being ported first; speculative decoding remains disabled until its integer arithmetic has a portable, reference-tested replacement.
- The model file reader, PMQ5 index loader, tokenizer integration, memory planner, and KV/DeltaNet state ownership still need to be adapted to Android app-private storage and the OpenCL backend.
- GPU workgroup sizes must be selected from real Vulkan/OpenCL device limits. PS4's fixed 64-lane assumptions are not accepted as proof of Mali subgroup behaviour.

## Current executable milestone

`GarlicAndroidBackend` uses a JNI bridge and a packaged clvk OpenCL-on-Vulkan runtime to enumerate the GPU, load a clspv-generated SPIR-V vector-add kernel, execute it, and verify the result. This is a backend smoke test, **not yet full Qwen inference**.

The clvk source checkout is patched at build time by `native/garlic_android/patch_clvk_for_precompiled_il.py`. The patch enables the SPIR-V IL extension without enabling a runtime compiler and makes `clCreateProgramWithIL` load the already-compiled SPIR-V words directly as a Vulkan executable. This avoids shipping Clang/LLVM/clspv executables in the Android APK. The MATRIX kernels contain no specialization constants; the patched no-compiler path is intentionally scoped to those precompiled Garlic modules.

The intended stack is:
- Garlic's original Qwen3.5 model and inference logic.
- clspv to compile adapted OpenCL C kernels to SPIR-V during the build.
- clvk, with a small documented patch, to execute precompiled SPIR-V through OpenCL on Android Vulkan.
- Android NDK/JNI for native memory, loading, scheduling and file integration.
- MATRIX's existing AmneziaWG backend for actual VPN execution.

No AI decision may bypass MATRIX's independent validation of VPN profiles, user authorization, or traffic-leak protections.
