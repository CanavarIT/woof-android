# Woof! for Android.
#
# Builds a single libmain.so (SDL_main entry point) together with:
#   - SDL3 (libSDL3.so, shared),
#   - SDL3_net (static),
#   - OpenAL Soft (static, OpenSL ES backend),
#   - Woof itself and its bundled third-party libraries.
# All third-party sources live in third_party/, no network access is needed to build.

cmake_minimum_required(VERSION 3.22.1)

project(Woof VERSION 16.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WOOF_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/woof")
set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

# Project metadata, as in the upstream CMakeLists.txt.
set(PROJECT_COMPANY "Fabian Greffrath and contributors")
set(PROJECT_COPYRIGHT "Copyright (C) 1993-2026")
set(PROJECT_LICENSE "GNU General Public License, version 2")
set(PROJECT_STRING "${PROJECT_NAME} ${PROJECT_VERSION}")
set(PROJECT_SHORTNAME "woof")
set(PROJECT_APPID "io.github.fabiangreffrath.woof")

# WoofSettings.cmake (target_woof_settings) is needed by Woof's sub-projects.
list(APPEND CMAKE_MODULE_PATH "${WOOF_ROOT}/cmake/modules")

include(CheckLibraryExists)
include(CheckSymbolExists)
include(CheckCSourceCompiles)

# ---------------------------------------------------------------------------
# Third-party libraries
# ---------------------------------------------------------------------------

# SDL3: shared library, required by the Java side (SDLActivity).
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SDL_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL_INSTALL_DOCS OFF CACHE BOOL "" FORCE)
set(SDL_ANDROID_JAR OFF CACHE BOOL "" FORCE)  # the Java side of SDL lives in app/src/main/java/org/libsdl/app
add_subdirectory("${THIRD_PARTY}/SDL3" SDL3-build)

# SDL3_net: static.
set(BUILD_SHARED_LIBS OFF)
set(SDLNET_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDLNET_INSTALL OFF CACHE BOOL "" FORCE)
set(SDLNET_INSTALL_MAN OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/SDL3_net" SDL3_net-build)

# OpenAL Soft: static, audio through OpenSL ES (part of the NDK, no Oboe needed).
set(LIBTYPE STATIC CACHE STRING "" FORCE)
set(ALSOFT_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_TESTS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_CONFIG OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_HRTF_DATA OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_AMBDEC_PRESETS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_BACKEND_OBOE OFF CACHE BOOL "" FORCE)
set(ALSOFT_BACKEND_SDL3 OFF CACHE BOOL "" FORCE)
if(ANDROID)
    set(ALSOFT_BACKEND_OPENSL ON CACHE BOOL "" FORCE)
    set(ALSOFT_REQUIRE_OPENSL ON CACHE BOOL "" FORCE)
endif()
add_subdirectory("${THIRD_PARTY}/openal-soft" openal-soft-build)

# ---------------------------------------------------------------------------
# config.h for Woof (mirrors the top-level Woof CMakeLists.txt)
# ---------------------------------------------------------------------------

check_library_exists(m pow "" HAVE_LIBM)
check_symbol_exists(strcasecmp "strings.h" HAVE_DECL_STRCASECMP)
check_symbol_exists(strncasecmp "strings.h" HAVE_DECL_STRNCASECMP)
check_c_source_compiles("
    typedef float quat __attribute__((ext_vector_type(4)));
    int main(void)
    {
        quat a;
        a.x = 0; a.y = 1; a.z = 2; a.w = 3;
        return 0;
    }
" HAVE_EXT_VECTOR_TYPE)

# What is enabled on Android:
#   OpenAL >= 1.22 -> buffer callback (PC speaker, streamed music).
#   libsndfile / FluidSynth / libxmp / Discord RPC are not built (see README.md).
set(HAVE_AL_BUFFER_CALLBACK TRUE)
set(THREADLOCAL_SPEC "_Thread_local")

# Woof's sub-projects include "${CMAKE_CURRENT_BINARY_DIR}/../" for config.h,
# so it is generated into <build>/woof/.
configure_file("${WOOF_ROOT}/config.h.in" "${CMAKE_BINARY_DIR}/woof/config.h")

add_subdirectory("${WOOF_ROOT}/third-party" woof/third-party)
add_subdirectory("${WOOF_ROOT}/opl" woof/opl)
add_subdirectory("${WOOF_ROOT}/textscreen" woof/textscreen)

# ---------------------------------------------------------------------------
# Main library
# ---------------------------------------------------------------------------

include(woof_sources.cmake)

add_library(main SHARED ${WOOF_SOURCES})
if(ANDROID)
    target_sources(main PRIVATE android_glue.c)
endif()

target_woof_settings(main)

target_include_directories(main PRIVATE
    "${CMAKE_BINARY_DIR}/woof"
    "${WOOF_ROOT}/src"
    "${THIRD_PARTY}/openal-soft/include/AL"
    "${MINIMP3_INCLUDE_DIRS}/minimp3")

target_compile_definitions(main PRIVATE "THREADLOCAL=${THREADLOCAL_SPEC}")

target_link_libraries(main PRIVATE
    SDL3::SDL3
    SDL3_net::SDL3_net
    OpenAL::OpenAL
    libebur128::libebur128
    yyjson::yyjson
    pffft::pffft
    miniz::miniz
    spng::spng
    base64
    md5
    sha1
    opl
    textscreen
    clap
    m)

if(ANDROID)
    target_link_libraries(main PRIVATE android log)
endif()

# Woof is C; OpenAL Soft (C++) is linked statically, so libc++ is required.
set_target_properties(main PROPERTIES LINKER_LANGUAGE CXX)
