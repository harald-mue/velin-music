SERVER_DIR := server
SERVER_BIN := $(SERVER_DIR)/bin/velin-server
GO := go

ANDROID_DIR := android
ANDROID_HOME ?= $(HOME)/Android/Sdk
export ANDROID_HOME

.PHONY: help clean server-build server-run server-test server-test-race server-lint server-fmt server-package android-build android-package android-test android-lint test docker-build docker-up docker-save package

help:
	@echo "Velin development targets:"
	@echo "  make server-build       Build server/bin/velin-server"
	@echo "  make server-run         Build and run the server"
	@echo "  make server-test        Run Go tests"
	@echo "  make server-test-race   Run Go tests with the race detector"
	@echo "  make server-lint        Run go vet"
	@echo "  make server-fmt         Format Go sources with gofmt"
	@echo "  make server-package     Build a stripped host binary into dist/velin-server"
	@echo "  make android-build      Build the Android debug APK"
	@echo "  make android-package   Build a signed release APK into dist/ (requires ~/Keystore signing env)"
	@echo "  make android-test       Run Android unit tests"
	@echo "  make android-lint       Run Android lint"
	@echo "  make package            Build server and Android packages into dist/"
	@echo "  make test               Run server and Android tests"
	@echo "  make docker-build       Build the scratch server image with Compose"
	@echo "  make docker-up          Build and start the Compose service"
	@echo "  make docker-save        Build and write dist/velin-server-local.tar.gz for copy to another host"
	@echo "  make clean              Remove build artifacts and test binaries"

server-build:
	mkdir -p $(SERVER_DIR)/bin
	cd $(SERVER_DIR) && $(GO) build -o bin/velin-server ./cmd/velin-server

server-run: server-build
	./$(SERVER_BIN)

server-test:
	cd $(SERVER_DIR) && $(GO) test ./...

server-test-race:
	cd $(SERVER_DIR) && $(GO) test -race ./...

server-lint:
	cd $(SERVER_DIR) && $(GO) vet ./...

server-fmt:
	cd $(SERVER_DIR) && gofmt -w $$(find . -name '*.go')

server-package:
	mkdir -p dist $(SERVER_DIR)/bin
	cd $(SERVER_DIR) && CGO_ENABLED=0 $(GO) build -trimpath -ldflags="-s -w" -o bin/velin-server ./cmd/velin-server
	cp $(SERVER_BIN) dist/velin-server
	@echo "Wrote dist/velin-server (host OS/arch, stripped)"

SIGNING_ENV ?= $(HOME)/Keystore/velin-android-signing.env

android-build:
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

android-package:
	@if [ ! -f "$(SIGNING_ENV)" ]; then \
		echo "Missing signing env $(SIGNING_ENV)"; \
		echo "Create a PKCS12 keystore outside the repo and write VELIN_ANDROID_KEYSTORE, VELIN_ANDROID_STORE_PASSWORD, VELIN_ANDROID_KEY_ALIAS, and VELIN_ANDROID_KEY_PASSWORD there."; \
		exit 1; \
	fi
	set -a && . "$(SIGNING_ENV)" && set +a && \
	if [ -z "$$VELIN_ANDROID_KEYSTORE" ] || [ ! -f "$$VELIN_ANDROID_KEYSTORE" ]; then \
		echo "VELIN_ANDROID_KEYSTORE is unset or not a file"; \
		exit 1; \
	fi && \
	if [ -z "$$VELIN_ANDROID_STORE_PASSWORD" ] || [ -z "$$VELIN_ANDROID_KEY_ALIAS" ] || [ -z "$$VELIN_ANDROID_KEY_PASSWORD" ]; then \
		echo "Signing env is missing store password, key alias, or key password"; \
		exit 1; \
	fi && \
	cd $(ANDROID_DIR) && ./gradlew assembleRelease -Pvelin.requireReleaseSigning=true
	mkdir -p dist
	@if [ -f $(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk ]; then \
		cp $(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk dist/velin-android.apk; \
		echo "Wrote dist/velin-android.apk (release, signed)"; \
	else \
		echo "assembleRelease did not produce a signed app-release.apk"; \
		exit 1; \
	fi

android-test:
	cd $(ANDROID_DIR) && ./gradlew testDebugUnitTest

android-lint:
	cd $(ANDROID_DIR) && ./gradlew lintDebug

docker-build:
	docker compose -f docker-compose.yml -f docker-compose.build.yml build

docker-up:
	docker compose -f docker-compose.yml -f docker-compose.build.yml up --build -d

docker-save: docker-build
	mkdir -p dist
	docker save velin-server:local | gzip > dist/velin-server-local.tar.gz
	@echo "Wrote dist/velin-server-local.tar.gz"
	@docker image inspect velin-server:local --format 'image={{.Id}} {{.Os}}/{{.Architecture}} size={{.Size}}'

test: server-test android-test

package: server-package android-package

clean:
	rm -rf $(SERVER_DIR)/bin $(ANDROID_DIR)/build $(ANDROID_DIR)/app/build
	rm -f $(SERVER_DIR)/velin-server $(SERVER_DIR)/*.test coverage.out $(SERVER_DIR)/coverage.out
