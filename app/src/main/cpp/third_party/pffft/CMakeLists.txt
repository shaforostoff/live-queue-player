# PFFFT, the SIMD transform the embedded build wants.
#
# Single precision only, which is why the width had to be settled before this
# arrived rather than alongside it. Measured against kiss at the sizes bpmcore
# uses, it is about six times faster and agrees to a float epsilon.
#
# No compile definitions are needed to reach the SIMD paths: pffft picks them
# from the compiler's own macros - _M_X64 or _M_IX86_FP>=1 for SSE, __aarch64__
# or __ARM_NEON for NEON - and the x86 build already asks for /arch:SSE2 at the
# top level, which is what sets _M_IX86_FP. `fft_backend_test` prints
# pffft_simd_size(), so a build that quietly fell back to scalar says so.

add_library(pffft STATIC pffft.c pffft.h)

# Consumers say <pffft/pffft.h>; pffft.c includes its own header unqualified.
target_include_directories(pffft
    PUBLIC  "${CMAKE_CURRENT_SOURCE_DIR}/.."
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

if(MSVC)
    # Third party C; do not let its warnings drown out ours. 4244 and 4305 are
    # the double-to-float narrowing a single precision library is made of.
    target_compile_options(pffft PRIVATE /W3 /wd4244 /wd4305 /wd4996)
endif()

set_target_properties(pffft PROPERTIES FOLDER "Third party")
