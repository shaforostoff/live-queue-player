# BPM analysis, ported from foo_rubato. See third_party/PROVENANCE.txt for the
# exact upstream revision and the checksum of every vendored file.
#
# One library name on every ABI. On arm64 it is the real analysis; everywhere
# else it is a stub whose nativeAvailable() returns false. The stub is not
# tidiness: an APK carrying no .so for an ABI is refused outright on that ABI -
# INSTALL_FAILED_NO_MATCHING_ABIS for a universal APK, and for an AAB the app is
# marked 64-bit-only and hidden from every 32-bit device on Play. Three tiny
# stubs are what keeps those users the whole player, rather than costing them it
# to give them a tempo they were never going to get.

cmake_minimum_required(VERSION 3.22.1)
project(bpmnative C CXX)   # C as well as CXX: pffft is C

if(ANDROID_ABI STREQUAL "arm64-v8a")

    # Set here rather than passed from build.gradle, so that a plain
    # `cmake -S app/src/main/cpp` builds the same thing a gradle build does.
    # fft_backend.cmake only fills in defaults - kiss and double - for what is
    # not already defined, and it makes pffft + double a FATAL_ERROR, so these
    # two lines are one decision rather than two. NEON needs no flag of its own:
    # pffft reaches it from __aarch64__.
    set(BPMCORE_FFT_BACKEND "pffft")
    set(BPMCORE_FFT_SCALAR  "float")

    # pffft first: bpmcore's own list file does target_link_libraries(bpmcore
    # PRIVATE pffft), so the target has to exist by the time it runs.
    add_subdirectory(third_party/pffft)
    add_subdirectory(third_party/bpmcore)

    # AGP configures debug variants as CMAKE_BUILD_TYPE=Debug, which is -O0 -
    # roughly an order of magnitude slower here, slow enough to change how the
    # feature behaves while it is being developed. There is nothing to step
    # through in a vendored transform, so both vendored targets are optimised in
    # every build type, and at the same level in every build type, so that a
    # number measured in one build means the same in the other.
    #
    # -O2, and deliberately no -ffast-math or -ffp-contract: bpmcore promises an
    # answer that does not move with the thread count, and the on-device result
    # is checked against one produced by a desktop build. Reordering float
    # arithmetic breaks both promises for a few percent.
    # -fvisibility=hidden on the vendored targets too, not just on bpmnative.
    # Static libraries keep default visibility otherwise, which put pffft's two
    # dozen C symbols - pffft_transform, cfftf1_ps and friends - in this .so's
    # dynamic symbol table, where nothing looks them up and another library
    # could collide with them.
    target_compile_options(pffft   PRIVATE -O2 -fvisibility=hidden)
    target_compile_options(bpmcore PRIVATE -O2 -fvisibility=hidden)

    add_library(bpmnative SHARED bpm_jni.cpp)
    # pffft directly as well as through bpmcore: the JNI layer calls
    # pffft_simd_size(), so a build that quietly fell back to the scalar path is
    # caught by a check at startup rather than by a user wondering why a folder
    # takes six times as long.
    target_link_libraries(bpmnative PRIVATE bpmcore pffft log)

else()

    add_library(bpmnative SHARED bpm_jni_stub.cpp)
    target_link_libraries(bpmnative PRIVATE log)

endif()

# JNIEXPORT already forces default visibility on the entry points, so hiding
# everything else costs nothing and leaves the dynamic symbol table holding only
# the functions Java actually looks up.
target_compile_options(bpmnative PRIVATE
    -fvisibility=hidden -fvisibility-inlines-hidden -Wall -Wextra)

# C++11 because bpmcore declares cxx_std_11 PUBLIC. Raising it buys nothing here
# and risks a behaviour change in a vendored file.
set_target_properties(bpmnative PROPERTIES
    CXX_STANDARD 11 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF)
