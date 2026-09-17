#!/bin/sh
# Verify the vendored BPM analysis: that the copy is the one PROVENANCE.txt
# names, and that it still measures a known tempo.
#
# The checksums matter more than they look. rhythm_model.h is 7546 lines of
# generated gradient-boosted trees; a corrupted copy does not fail to compile,
# it classifies wrongly and quietly. Nothing else here would catch that.
set -e
here=$(cd "$(dirname "$0")" && pwd)
vendor="$here/../../app/src/main/cpp/third_party"

echo "== checksums =="
( cd "$vendor" && sed -n '/^[0-9a-f]\{64\}  /p' PROVENANCE.txt | sha256sum -c - )

if [ -d "${FOO_RUBATO:-/home/nick/foo_rubato}" ]; then
	echo
	echo "== against the source tree =="
	for d in bpmcore pffft; do
		diff -r "${FOO_RUBATO:-/home/nick/foo_rubato}/$d" "$vendor/$d" \
			&& echo "$d: identical"
	done
	diff "${FOO_RUBATO:-/home/nick/foo_rubato}/cmake/fft_backend.cmake" \
	     "$vendor/cmake/fft_backend.cmake" && echo "fft_backend.cmake: identical"
fi

echo
echo "== build =="
cmake -S "$here" -B "$here/build" -DCMAKE_BUILD_TYPE=Release > /dev/null
cmake --build "$here/build" -j "$(nproc 2>/dev/null || echo 2)" > /dev/null

echo
echo "== the analysis =="
"$here/build/bpmcheck"

if [ -x "$here/build/bpmjnicheck" ]; then
	echo
	echo "== the JNI layer =="
	"$here/build/bpmjnicheck"
else
	echo
	echo "(no host jni.h, so the JNI layer was not checked)"
fi
