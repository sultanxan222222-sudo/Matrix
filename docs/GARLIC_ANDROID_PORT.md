# MATRIX: Garlic LLM Android Frankenbuild

## Product decision

MATRIX must use **Garlic LLM itself as the inference/model foundation**. Do not replace Garlic with LiteRT-LM, llama.cpp, or another complete LLM runtime. External projects are selected only to replace the PS4-specific platform pieces that prevent Garlic from running on Android.

Garlic's Qwen3.5 model, model layout/converters, tokenizer, inference flow, quantization formats, and network-independent operation remain the intellectual core. MATRIX's existing AmneziaWG backend remains responsible for creating and protecting the VPN tunnel.

## Component stack

| Layer | Selected source | Responsibility |
|---|---|---|
| Model and inference logic | `leonardo-alegria/garlic-llm` | Preserve Qwen3.5, tokenizer, PMQ5 model index, Q4S/Q3S weight formats, Gated DeltaNet and attention logic |
| OpenCL-on-Vulkan runtime | `kpet/clvk` (Apache-2.0) | Provide an OpenCL API on top of the Android device's Vulkan driver |
| OpenCL-to-SPIR-V compiler | `google/clspv` (Apache-2.0) | Compile adapted Garlic OpenCL C kernels on the build machine into Vulkan SPIR-V assets |
| Android native platform | Android NDK + CMake + JNI | Replace Orbis memory, GNM submission, timing, file paths and process integration |
| VPN execution | Existing MATRIX AmneziaWG Android backend | Execute only validated profile changes; preserve tunnel security and user authorization |
| Policy controller | MATRIX Kotlin/native bridge | Supply aggregate telemetry; validate AI recommendations; probe alternatives; apply safe failover |

The Android build should package a prebuilt ARM64 `libOpenCL.so` from clvk and precompiled SPIR-V files. The documented clvk Android route uses the Android NDK and permits disabling its runtime compiler when kernels have already been compiled on the host.

## What remains from Garlic

- Qwen3.5 architecture and the model-specific decode path.
- Original tokenizer and model-index semantics.
- Existing Q4/Q3 quantization and tensor layouts, including the 9B Q4S/Q3S layouts where their kernels can be ported faithfully.
- The Gated DeltaNet state update, attention/cache algorithms, sampling and inference orchestration.
- Original model conversion/reference scripts, subject to their existing dependencies and notices.

The large model weights should live in app-private storage as a separately installed/downloaded model pack, not be silently embedded into the base APK. The original 9B pack and its memory requirements must be tested on the actual target phone before claiming support.

## What must be replaced

1. Remove PS4 UI, DualShock/Pad, video output, microphone/audio, ASR/TTS, Python execution and chat/API layers. MATRIX AI is an internal network-policy engine, not a chat feature.
2. Replace `sceKernelAllocateDirectMemory`, `sceKernelMapDirectMemory`, PS4 direct-memory addresses and the Garlic-memory bump allocator with OpenCL buffers (`cl_mem`) and explicit host/device transfers or mappings.
3. Replace hand-written GNM/PM4 packets, kernel descriptors from the PS4 `kernels.bin`, `sceGnmSubmitCommandBuffers`, cache flush packets and GPU polling with OpenCL program/kernel objects, `clSetKernelArg`, `clEnqueueNDRangeKernel`, event/fence completion and correct buffer synchronization.
4. Replace PS4-only timing, paths, logging and file APIs with Android NDK/POSIX equivalents and app-private storage.
5. Keep the CPU-side engine orchestration, but change GPU addresses into typed buffer handles. A PS4 GPU address is not a valid Android Vulkan/OpenCL buffer handle.

## Kernel portability work

Garlic's shader sources are OpenCL C, but they are not portable OpenCL yet: `shaders/common.h` contains GCN-specific work-item/work-group built-ins, AMD fences/barriers, reciprocal-square-root instructions and bit-cast intrinsics. The source also uses `__builtin_amdgcn_readfirstlane` and no-op AMD inline-assembly constraints; the 9B SWAR path contains a real `v_mad_i32_i24` instruction.

Create an Android shader variant rather than rewriting the math blindly:

- Map work-item and work-group IDs to standard OpenCL built-ins.
- Map work-group synchronization to OpenCL barriers with the correct memory fences.
- Replace AMD math/bit-cast intrinsics with equivalent OpenCL C operations and verify numerical tolerance.
- Remove only truly no-op inline assembly.
- Replace the SWAR 24-bit multiply-add with an exactly tested portable integer implementation, or temporarily route that operation through a slower correctness-first kernel. Do not silently change Q4S/Q3S decoding.
- Compile each kernel with clspv and validate the resulting SPIR-V. clspv targets a subset of OpenCL C 1.2; unsupported constructs and dynamic-loop restrictions must be checked kernel by kernel.

The first GPU milestone should be the Qwen3.5 main decode kernels, not speech, speculative decoding or the chat UI. Speculative decoding can be enabled only after the base decode path has correctness tests.

## Runtime safeguards

- Detect the phone's Vulkan API version, compute queue, max workgroup invocation count, workgroup storage, required integer/float features and memory budget at runtime.
- Never assume desktop GCN wave size, 256/512-thread workgroups, fp16 storage or subgroup operations. Build/choose variants only when the device reports support.
- Run all model loading, compilation, dispatch and waiting on a dedicated worker thread, never the Android UI thread.
- Keep a CPU fallback for correctness and unsupported GPU configurations.
- Compare Android GPU outputs against Garlic's existing CPU/reference implementations on fixed inputs, especially Q4/Q3 matvec, RoPE/attention, DeltaNet state and logits.
- The AI returns a bounded recommendation only. MATRIX's policy controller independently checks that a candidate is configured and authenticated, probes it, then decides whether failover is safe. The AI must never disable kill-switch or traffic-leak protections.

## Build and verification sequence

1. Build clvk for `arm64-v8a` with the NDK; package the resulting OpenCL shared library.
2. Build clspv for the host CI environment and add a repeatable shader compilation step.
3. Add the Android-specific compatibility header and compile a small Garlic kernel first.
4. Add the OpenCL engine backend behind Garlic's existing engine interface; retain the PS4 backend in its own target rather than mixing platform code.
5. Port and test kernels in dependency order: copy/embedding and norms; Q4/Q3 matvec; attention/cache; DeltaNet; full model decode.
6. Port model loading and tokenizer into the Android native library; test with a small, legally redistributable test model before installing the 9B pack.
7. Connect the model to MATRIX telemetry and safe profile-selection logic.
8. Run CI builds, numerical reference tests, then real-device tests on the target Realme phone. A CI APK build alone is not proof that its GPU driver runs the kernels.

## Licensing

Garlic application code is GPL-3.0; Qwen weights retain their separate model licence. clvk and clspv are Apache-2.0. Preserve notices and third-party licences. Before distributing a combined APK, keep source and licence obligations for Garlic modifications explicit.
