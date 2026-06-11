cmake_minimum_required(VERSION 3.22.1)

project(neutts_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 1. Configure and include llama.cpp (disable all non-essential build targets)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "llama: build common library" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "llama: build app" FORCE)

add_subdirectory(llama.cpp)

# 2. Configure and include espeak-ng manually
set(ESPEAK_SRC_DIR espeak-ng/src)
set(ESPEAK_NG_DIR ${ESPEAK_SRC_DIR}/libespeak-ng)
set(UCD_DIR ${ESPEAK_SRC_DIR}/ucd-tools)

add_library(espeak-ng STATIC
    # ucd-tools
    ${UCD_DIR}/src/case.c
    ${UCD_DIR}/src/categories.c
    ${UCD_DIR}/src/ctype.c
    ${UCD_DIR}/src/proplist.c
    ${UCD_DIR}/src/scripts.c
    ${UCD_DIR}/src/tostring.c

    # libespeak-ng C source files
    ${ESPEAK_NG_DIR}/common.c
    ${ESPEAK_NG_DIR}/mnemonics.c
    ${ESPEAK_NG_DIR}/error.c
    ${ESPEAK_NG_DIR}/ieee80.c
    ${ESPEAK_NG_DIR}/compiledata.c
    ${ESPEAK_NG_DIR}/compiledict.c
    ${ESPEAK_NG_DIR}/dictionary.c
    ${ESPEAK_NG_DIR}/encoding.c
    ${ESPEAK_NG_DIR}/intonation.c
    ${ESPEAK_NG_DIR}/langopts.c
    ${ESPEAK_NG_DIR}/numbers.c
    ${ESPEAK_NG_DIR}/phoneme.c
    ${ESPEAK_NG_DIR}/phonemelist.c
    ${ESPEAK_NG_DIR}/readclause.c
    ${ESPEAK_NG_DIR}/setlengths.c
    ${ESPEAK_NG_DIR}/soundicon.c
    ${ESPEAK_NG_DIR}/spect.c
    ${ESPEAK_NG_DIR}/ssml.c
    ${ESPEAK_NG_DIR}/synthdata.c
    ${ESPEAK_NG_DIR}/synthesize.c
    ${ESPEAK_NG_DIR}/tr_languages.c
    ${ESPEAK_NG_DIR}/translate.c
    ${ESPEAK_NG_DIR}/translateword.c
    ${ESPEAK_NG_DIR}/voices.c
    ${ESPEAK_NG_DIR}/wavegen.c
    ${ESPEAK_NG_DIR}/speech.c
    ${ESPEAK_NG_DIR}/espeak_api.c
)

target_include_directories(espeak-ng PUBLIC
    espeak-config
    ${ESPEAK_SRC_DIR}/include
    ${ESPEAK_SRC_DIR}/include/compat
    ${UCD_DIR}/src/include
)

target_compile_definitions(espeak-ng PRIVATE "LIBESPEAK_NG_EXPORT=1")
target_compile_options(espeak-ng PRIVATE
    -fPIC
    -fvisibility=hidden
    -fno-exceptions
    -fwrapv
)

# 3. Build JNI Bridge
find_library(log-lib log)

add_library(neutts_jni SHARED
    neutts_jni.cpp
)

target_include_directories(neutts_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    espeak-config
    ${ESPEAK_SRC_DIR}/include
)

target_link_libraries(neutts_jni PRIVATE
    llama
    espeak-ng
    ${log-lib}
)
