cmake_minimum_required(VERSION 3.22.1)
project(matrix_garlic_android LANGUAGES C CXX)

add_library(matrix_garlic SHARED
    garlic_opencl_probe.cpp
    garlic_opencl_runtime.cpp
    garlic/engine_android.c
)

target_include_directories(matrix_garlic PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/garlic-llm/chat
)

target_compile_definitions(matrix_garlic PRIVATE MATRIX_ANDROID=1)

target_compile_features(matrix_garlic PRIVATE cxx_std_17)
target_compile_options(matrix_garlic PRIVATE -Wall -Wextra -Werror=return-type)

find_library(ANDROID_LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)
find_library(DL_LIB dl REQUIRED)

target_link_libraries(matrix_garlic PRIVATE
    ${ANDROID_LOG_LIB}
    ${ANDROID_LIB}
    ${DL_LIB}
)
