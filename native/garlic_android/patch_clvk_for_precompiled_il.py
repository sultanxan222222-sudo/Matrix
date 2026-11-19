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


if __name__ == "__main__":
    main()
