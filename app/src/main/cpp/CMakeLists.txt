cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

# 앱에 번들된 whisper.cpp 소스 위치
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# whisper.cpp 버전 추출
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

message(STATUS " Whisper version: ${WHISPER_VERSION}")

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

# ---------- RNNoise (Xiph, BSD-3) : 신경망 노이즈 억제 ----------
# 소스는 git 서브모듈(app/src/main/cpp/rnnoise).
# 모델 가중치(rnnoise_data_little.c)는 git 에 없으므로 configure 시 다운로드해 생성한다.
# arm64 는 NEON 자동 활성(vec_neon.h).
set(RNNOISE_DIR ${CMAKE_SOURCE_DIR}/rnnoise)

# 서브모듈의 model_version(=tar 의 sha256)을 읽어 경량 모델을 받아 추출
file(READ "${RNNOISE_DIR}/model_version" RN_MODEL_HASH)
string(STRIP "${RN_MODEL_HASH}" RN_MODEL_HASH)
set(RN_MODEL_TGZ "${CMAKE_BINARY_DIR}/rnnoise_data-${RN_MODEL_HASH}.tar.gz")
set(RN_MODEL_DIR "${CMAKE_BINARY_DIR}/rnnoise_model")
set(RN_MODEL_C   "${RN_MODEL_DIR}/src/rnnoise_data_little.c")
if(NOT EXISTS "${RN_MODEL_C}")
    message(STATUS "Downloading RNNoise model ${RN_MODEL_HASH}")
    file(DOWNLOAD
        "https://media.xiph.org/rnnoise/models/rnnoise_data-${RN_MODEL_HASH}.tar.gz"
        "${RN_MODEL_TGZ}"
        EXPECTED_HASH SHA256=${RN_MODEL_HASH}
        SHOW_PROGRESS)
    file(MAKE_DIRECTORY "${RN_MODEL_DIR}")
    file(ARCHIVE_EXTRACT INPUT "${RN_MODEL_TGZ}" DESTINATION "${RN_MODEL_DIR}")
endif()

set(RNNOISE_SOURCES
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/kiss_fft.c
    ${RNNOISE_DIR}/src/nnet.c
    ${RNNOISE_DIR}/src/nnet_default.c
    ${RNNOISE_DIR}/src/parse_lpcnet_weights.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/rnnoise_tables.c
    ${RN_MODEL_C}
)
add_library(rnnoise STATIC ${RNNOISE_SOURCES})
set_property(TARGET rnnoise PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(rnnoise
    PUBLIC ${RNNOISE_DIR}/include
    PRIVATE ${RNNOISE_DIR}/src ${RN_MODEL_DIR}/src
)
target_compile_options(rnnoise PRIVATE -O3 -fvisibility=hidden)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS                      -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS                      -mfpu=neon-vfpv4)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif ()

    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml rnnoise)

    target_include_directories(${target_name} PRIVATE
        ${WHISPER_LIB_DIR}
        ${WHISPER_LIB_DIR}/src
        ${WHISPER_LIB_DIR}/include
        ${WHISPER_LIB_DIR}/ggml/include
        ${WHISPER_LIB_DIR}/ggml/src
        ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
        ${RNNOISE_DIR}/include
    )
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper") # 기본 타겟
