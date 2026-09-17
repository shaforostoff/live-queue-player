# Host build of the vendored bpmcore, for checking the copy on a desktop.
#
# Not part of the Android build - app/src/main/cpp/CMakeLists.txt never descends
# here, and AGP compiles only what that file declares. This exists so the
# analysis can be exercised without a phone, at exactly the backend and width
# the app ships (pffft, single precision), which is NOT bpmcore's own default
# (kiss, double). Run it through ../../tools/bpmcheck/run.sh.
cmake_minimum_required(VERSION 3.21)
project(bpmcheck C CXX)

set(BPMCORE_VENDOR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/third_party"
    CACHE PATH "The vendored bpmcore tree to check")

# Set before either subdirectory, exactly as the Android build does:
# fft_backend.cmake only fills in defaults for what is not already defined.
set(BPMCORE_FFT_BACKEND "pffft")
set(BPMCORE_FFT_SCALAR  "float")

add_subdirectory("${BPMCORE_VENDOR}/pffft"   pffft-build)
add_subdirectory("${BPMCORE_VENDOR}/bpmcore" bpmcore-build)

add_executable(bpmcheck main.cpp)
target_link_libraries(bpmcheck PRIVATE bpmcore pffft)

# The JNI layer, driven through a hand-built JNIEnv. Only built where a host JDK
# is around to supply jni.h, because that is the only thing it needs from one -
# there is no JVM in this, and nothing is loaded.
find_path(JNI_H jni.h PATHS
    /usr/lib/jvm/default-java/include
    /usr/lib/jvm/java-21-openjdk-amd64/include
    /usr/lib/jvm/java-17-openjdk-amd64/include
    "$ENV{JAVA_HOME}/include")
if(JNI_H)
    add_executable(bpmjnicheck jni_harness.cpp
        "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/bpm_jni.cpp")
    target_include_directories(bpmjnicheck PRIVATE "${JNI_H}" "${JNI_H}/linux")
    target_link_libraries(bpmjnicheck PRIVATE bpmcore pffft)
else()
    message(STATUS "No host jni.h found; skipping bpmjnicheck")
endif()
