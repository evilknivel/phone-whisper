SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
APK        := app/build/outputs/apk/debug/app-debug.apk

SHERPA_VERSION := 1.13.8
SHERPA_SHA256  := 2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44
JNI_DIR        := app/src/main/jniLibs/arm64-v8a
JNI_LIBS       := $(JNI_DIR)/libsherpa-onnx-jni.so $(JNI_DIR)/libonnxruntime.so

# Defaults for macOS/Homebrew; elsewhere set JAVA_HOME/ANDROID_HOME (or local.properties) yourself.
ifeq ($(shell uname),Darwin)
JAVA_HOME    ?= /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
endif
ifneq ($(JAVA_HOME),)
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)
endif
ifneq ($(ANDROID_HOME),)
export ANDROID_HOME
endif
ADB := $(if $(ANDROID_HOME),$(ANDROID_HOME)/platform-tools/adb,adb)

# Desktop JNI build of the same sherpa-onnx version, for the integration test.
SHERPA_LINUX_JNI := build/sherpa-onnx-v$(SHERPA_VERSION)-linux-x64-jni/lib

.PHONY: build test integration-test install adb-install push-model jni-libs clean

build: $(JNI_LIBS)
	./gradlew assembleDebug
	@echo "APK: $(APK)"

test:
	./gradlew testDebugUnitTest

## Load every catalog model through the real JNI and transcribe a WAV (Linux x64).
## Usage: make integration-test MODELS=/dir/with/one/subdir/per/model WAV=/path/en.wav
integration-test: $(SHERPA_LINUX_JNI)
	@test -n "$(MODELS)" -a -n "$(WAV)" || (echo "Usage: make integration-test MODELS=... WAV=..." && exit 1)
	set -o pipefail; SHERPA_JNI_DIR=$(abspath $(SHERPA_LINUX_JNI)) LD_LIBRARY_PATH=$(abspath $(SHERPA_LINUX_JNI)) \
	PHONE_WHISPER_MODELS_DIR=$(abspath $(MODELS)) PHONE_WHISPER_TEST_WAV=$(abspath $(WAV)) \
	./gradlew testDebugUnitTest --tests '*LocalTranscriberIntegrationTest*' --rerun -i | grep -E '^ *(Parakeet|Whisper|Moonshine)|BUILD|FAIL'

$(SHERPA_LINUX_JNI):
	mkdir -p build
	curl -fSL https://github.com/k2-fsa/sherpa-onnx/releases/download/v$(SHERPA_VERSION)/sherpa-onnx-v$(SHERPA_VERSION)-linux-x64-jni.tar.bz2 | tar xjf - -C build

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/phone-whisper.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/phone-whisper.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ADB) install -r $(APK)

## Re-download the committed sherpa-onnx native libs into jniLibs/ if they are missing
jni-libs: $(JNI_LIBS)

$(JNI_LIBS):
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	echo "Downloading sherpa-onnx v$(SHERPA_VERSION) Android libs..." && \
	curl -fSL -o "$$tmp/sherpa.tar.bz2" https://github.com/k2-fsa/sherpa-onnx/releases/download/v$(SHERPA_VERSION)/sherpa-onnx-v$(SHERPA_VERSION)-android.tar.bz2 && \
	echo "$(SHERPA_SHA256)  $$tmp/sherpa.tar.bz2" | shasum -a 256 -c - && \
	tar xjf "$$tmp/sherpa.tar.bz2" -C "$$tmp" ./jniLibs/arm64-v8a && \
	mkdir -p $(JNI_DIR) && \
	cp "$$tmp/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$$tmp/jniLibs/arm64-v8a/libonnxruntime.so" $(JNI_DIR)/

## Push a model to the phone's internal storage (usage: make push-model MODEL=/path/to/model-dir)
push-model:
	@test -n "$(MODEL)" || (echo "Usage: make push-model MODEL=/path/to/model-dir" && exit 1)
	$(ADB) push $(MODEL)/ /data/local/tmp/$(notdir $(MODEL))/
	$(ADB) shell "run-as com.kafkasl.phonewhisper mkdir -p files/models/$(notdir $(MODEL))"
	@for f in $$(ls $(MODEL)/*.onnx $(MODEL)/*.ort $(MODEL)/*.txt 2>/dev/null); do \
		echo "  copying $$(basename $$f)..."; \
		$(ADB) shell "run-as com.kafkasl.phonewhisper cp /data/local/tmp/$(notdir $(MODEL))/$$(basename $$f) files/models/$(notdir $(MODEL))/$$(basename $$f)"; \
	done
	@echo "Model pushed: $(notdir $(MODEL))"

clean:
	./gradlew clean
