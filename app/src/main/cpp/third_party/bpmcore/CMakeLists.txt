# The analysis, with no host in it.
#
# Nothing here includes foobar2000, pfc, ATL or windows.h - only the standard
# library and one FFT - so the same sources build for a command line tool, a
# macOS host or an Android target. The foobar2000 component is a shell around
# this, and `real_fft.h` is the only place a transform is named.

add_library(bpmcore STATIC
    analyse.cpp
    odf.cpp
    real_fft.cpp
    resample.cpp
    rhythm.cpp
    tempo.cpp

    bpmcore.h
    internal.h
    parallel.h
    real_fft.h
    rhythm_model.h)

# Consumers say #include <bpmcore/bpmcore.h>.
target_include_directories(bpmcore PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/..")

# Which transform, and at what width. Both are PUBLIC where they have to be
# seen by a consumer's headers: fft_scalar is part of real_fft.h's meaning, and
# a test that links bpmcore and reads its bins has to agree about the layout.
include("${CMAKE_CURRENT_SOURCE_DIR}/../cmake/fft_backend.cmake")

# Both backends are named, rather than kiss being the absence of pffft. A
# consumer that has to say which transform is in the build - foo_rubato's about
# box credits its licence - can then tell "kiss" from "these definitions never
# arrived", and fail the compile instead of printing a guess.
if(BPMCORE_FFT_BACKEND STREQUAL "pffft")
    target_link_libraries(bpmcore PRIVATE pffft)
    target_compile_definitions(bpmcore PUBLIC BPMCORE_FFT_PFFFT)
else()
    target_link_libraries(bpmcore PRIVATE kiss_fft)
    target_compile_definitions(bpmcore PUBLIC BPMCORE_FFT_KISS)
endif()
target_compile_definitions(bpmcore PUBLIC BPMCORE_FFT_SCALAR_TYPE=${BPMCORE_FFT_SCALAR})

target_compile_features(bpmcore PUBLIC cxx_std_11)

if(MSVC)
    target_compile_options(bpmcore PRIVATE /W4)
    # windows.h is not included here, but a consumer's precompiled header may
    # have dragged in its min/max macros before this target's headers.
    target_compile_definitions(bpmcore PUBLIC NOMINMAX)
else()
    target_compile_options(bpmcore PRIVATE -Wall -Wextra
        # real_fft holds one set of fields for both backends, and the two
        # pffft needs - its scratch buffer and the frame size it wants passed
        # back - are never read in a kiss build. Keeping the layout the same
        # either way is worth more than the two fields, and [[maybe_unused]] is
        # not available to a member while this target still offers to compile
        # as C++11.
        -Wno-unused-private-field)
endif()

set_target_properties(bpmcore PROPERTIES FOLDER "bpmcore")
