#!/usr/bin/env python3
"""Patch clvk for MATRIX's precompiled-SPIR-V-only Android runtime.

The app compiles Garlic OpenCL C kernels to SPIR-V on the host with clspv.
The Android runtime must not ship Clang/LLVM/clspv or try to run a compiler.
This small patch teaches clvk to accept an already-compiled SPIR-V module as
an executable when compiler support is disabled. It deliberately disables
runtime source compilation and specialization-constant reflection.
"""

from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str, description: str) -> None:
    source = path.read_text(encoding="utf-8")
    if new in source:
        print(f"already patched: {description}")
        return
    if old not in source:
        raise SystemExit(
            f"Cannot patch {description}: expected source anchor was not found in {path}"
        )
    path.write_text(source.replace(old, new, 1), encoding="utf-8")
    print(f"patched: {description}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_clvk_for_precompiled_il.py <clvk-source-dir>")
    root = Path(sys.argv[1]).resolve()
    cmake = root / "src" / "CMakeLists.txt"
    program = root / "src" / "program.cpp"
    if not cmake.is_file() or not program.is_file():
        raise SystemExit(f"Not a clvk checkout: {root}")

    replace_once(
        cmake,
        """if (CLVK_COMPILER_AVAILABLE)
    target_compile_definitions(clvk-config-definitions INTERFACE COMPILER_AVAILABLE=1)
    if (CLVK_ENABLE_SPIRV_IL)
        target_compile_definitions(clvk-config-definitions INTERFACE ENABLE_SPIRV_IL=1)
    endif()
endif()""",
        """if (CLVK_COMPILER_AVAILABLE)
    target_compile_definitions(clvk-config-definitions INTERFACE COMPILER_AVAILABLE=1)
endif()
# MATRIX compiles all Garlic OpenCL C kernels ahead of time with clspv.
# Keep SPIR-V IL enabled even though the Android runtime has no compiler.
if (CLVK_ENABLE_SPIRV_IL)
    target_compile_definitions(clvk-config-definitions INTERFACE ENABLE_SPIRV_IL=1)
endif()""",
        "expose SPIR-V IL without enabling an on-device compiler",
    )

    replace_once(
        program,
        """cl_int cvk_program::parse_user_spec_constants() {
#if COMPILER_AVAILABLE && ENABLE_SPIRV_IL""",
        """cl_int cvk_program::parse_user_spec_constants() {
#if !COMPILER_AVAILABLE && ENABLE_SPIRV_IL
    // MATRIX's packaged Garlic kernels contain no specialization constants.
    // Keep the precompiled-SPIR-V path usable without bundling LLVM tooling.
    return CL_SUCCESS;
#elif COMPILER_AVAILABLE && ENABLE_SPIRV_IL""",
        "skip unused specialization-constant reflection in precompiled-only mode",
    )

    replace_once(
        program,
        """cl_build_status cvk_program::do_build_inner(const cvk_device* device) {
#if !COMPILER_AVAILABLE
    UNUSED(device);
#else""",
        """cl_build_status cvk_program::do_build_inner(const cvk_device* device) {
#if !COMPILER_AVAILABLE
    UNUSED(device);
#if ENABLE_SPIRV_IL
    // clCreateProgramWithIL supplied a complete SPIR-V module. No compiler or
    // translator is needed: pass the words directly to clvk's Vulkan loader.
    if (m_operation != build_operation::build || m_il.empty() ||
        (m_il.size() % sizeof(uint32_t)) != 0) {
        return CL_BUILD_ERROR;
    }
    std::vector<uint32_t> spirv(m_il.size() / sizeof(uint32_t));
    memcpy(spirv.data(), m_il.data(), m_il.size());
    m_binary->use(std::move(spirv));
    m_binary_type = CL_PROGRAM_BINARY_TYPE_EXECUTABLE;
#else
    return CL_BUILD_ERROR;
#endif
#else""",
        "load precompiled SPIR-V directly as a Vulkan executable",
    )


    # clvk's optional VK_KHR_shader_fma declarations are newer than the Vulkan
    # headers shipped in Android NDK 27.2. MATRIX's precompiled kernels do not
    # require this optional feature, so remove only its optional discovery path.
    import re

    def remove_once(path: Path, pattern: str, description: str) -> None:
        source = path.read_text(encoding="utf-8")
        updated, count = re.subn(pattern, "", source, count=1, flags=re.MULTILINE | re.DOTALL)
        if count == 0:
            # The source is pinned to one audited clvk commit. An absent optional
            # feature declaration means this checkout already lacks that path.
            print(f"already absent or patched: {description}")
            return
        path.write_text(updated, encoding="utf-8")
        print(f"patched: {description}")

    device_h = root / "src" / "device.hpp"
    device_cpp = root / "src" / "device.cpp"
    remove_once(
        device_h,
        r"^\s*VkPhysicalDeviceShaderFmaFeaturesKHR m_features_shader_fma\{\};\n",
        "remove unavailable Shader FMA feature member",
    )
    remove_once(
        device_cpp,
        r"^\s*VK_KHR_SHADER_FMA_EXTENSION_NAME,\n",
        "remove optional Shader FMA extension name",
    )
    remove_once(
        device_cpp,
        r"^\s*m_features_shader_fma\.sType =\n\s*VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FMA_FEATURES_KHR;\n",
        "remove unavailable Shader FMA feature initialization",
    )
    remove_once(
        device_cpp,
        r"^\s*VER_EXT_FEAT\(0, VK_KHR_SHADER_FMA_EXTENSION_NAME,\n\s*m_features_shader_fma\),\n",
        "omit optional Shader FMA feature query",
    )
    remove_once(
        device_cpp,
        r"^    std::vector<std::string> spvKhrFMA;\n.*?^    }\n",
        "disable optional Shader FMA compiler advertisement",
    )
    remove_once(
        device_cpp,
        r"^    case spv::CapabilityFMAKHR:\n        return m_features_shader_fma\.shaderFmaFloat16 \|\n               m_features_shader_fma\.shaderFmaFloat32 \|\n               m_features_shader_fma\.shaderFmaFloat64;\n",
        "treat optional Shader FMA capability as unsupported",
    )



if __name__ == "__main__":
    main()
