# Third-party code and licence notices

MATRIX's Garlic-first Android compute port combines the following upstream components. Their sources are fetched by `native/garlic_android/build_garlic_android.sh` and their licence texts are retained in this directory.

| Component | Purpose | Licence | Upstream |
|---|---|---|---|
| Garlic LLM | Qwen3.5 model format, tokenizer, inference and adapted kernels | GPL-3.0-or-later | https://github.com/leonardo-alegria/garlic-llm |
| clvk | OpenCL execution on Vulkan | Apache-2.0 | https://github.com/kpet/clvk |
| clspv | Compile OpenCL C to SPIR-V | Apache-2.0 | https://github.com/google/clspv |
| OpenCL-Headers | OpenCL API headers | Apache-2.0 | https://github.com/KhronosGroup/OpenCL-Headers |
| SPIRV-Headers | SPIR-V headers and grammar | Apache-2.0 | https://github.com/KhronosGroup/SPIRV-Headers |
| SPIRV-Tools | SPIR-V validation and toolchain support | Apache-2.0 | https://github.com/KhronosGroup/SPIRV-Tools |

The CLVK build also consumes the SPIRV-LLVM-Translator and LLVM/Clang/libclc source revisions pinned by its submodules/dependency manifest. Their original notices remain in the fetched source trees used for the build; the distributable notice bundle must be refreshed if the pinned dependency set changes.

The Qwen3.5 model weights are not covered by Garlic's GPL licence. Obtain and distribute any model pack only under the model's own licence and retain its model card and notices.

Do not treat an upstream repository's open-source status as permission to remove its licence notices or to ignore copyleft requirements. MATRIX changes to Garlic-derived code must preserve the GPL terms.
