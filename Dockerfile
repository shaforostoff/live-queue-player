FROM debian:bookworm-slim AS builder

# need git, openjdk and android sdk
# unzip is for the ABI check near the end of this file, not for the SDK.
RUN apt-get update && apt-get install -y git openjdk-17-jdk-headless sdkmanager unzip

# get required android sdk
#
# The NDK and CMake are named rather than left to AGP's auto-download. AGP would
# very likely fetch them - the licences are accepted below, and this image
# already relies on auto-download for platforms;android-36, since compileSdk is
# 36 and only 33 is installed here - but a ~2.5GB implicit fetch in the middle
# of a build is the kind of thing that becomes an unexplained CI timeout. The
# NDK version is also the one the analysis's numbers were checked at, so it
# belongs somewhere visible. It must match ndkVersion in app/build.gradle.
RUN sdkmanager "build-tools;30.0.3" "platforms;android-33" \
               "ndk;28.2.13676358" "cmake;3.22.1"

# accept licenses for android sdk
RUN yes | sdkmanager --licenses

WORKDIR /home

# create a debug keystore for signing apk
RUN keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

# copy source code
COPY . /home/little-music-player

# enter project folder
WORKDIR /home/little-music-player

# create a keystore.properties file in the PARENT DIRECTORY of little-file-explorer
RUN echo "\
storePassword=android\n\
keyPassword=android\n\
keyAlias=androiddebugkey\n\
storeFile=/home/debug.keystore\
" > keystore.properties

# run the gradlew wrapper to build apk
RUN chmod 700 ./gradlew

RUN ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug --no-daemon --console=plain

# An APK carrying no .so for an ABI is uninstallable on that ABI, so a mistake in
# abiFilters does not fail the build - it silently ships an app that 32-bit and
# x86 devices cannot install at all. Nothing else here would catch that, and the
# stub in bpm_jni_stub.cpp exists precisely to keep those entries present.
RUN for abi in arm64-v8a armeabi-v7a x86 x86_64; do \
        unzip -l app/build/outputs/apk/debug/app-debug.apk \
            | grep -q "lib/$abi/libbpmnative.so" \
            || { echo "missing lib/$abi/libbpmnative.so in the APK"; exit 1; }; \
        echo "lib/$abi/libbpmnative.so present"; \
    done

FROM scratch AS output

COPY --from=builder /home/little-music-player/app/build/outputs/apk/debug/app-debug.apk .
