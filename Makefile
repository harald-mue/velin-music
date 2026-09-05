SERVER_DIR := server
SERVER_BIN := $(SERVER_DIR)/bin/velin-server
GO := go

ANDROID_DIR := android
ANDROID_HOME ?= $(HOME)/Android/Sdk
export ANDROID_HOME

.PHONY: help clean server-build server-run server-test server-test-race server-lint server-fmt android-build android-test android-lint test docker-build docker-up docker-save

help:
	@echo "Velin development targets:"
	@echo "  make server-build       Build server/bin/velin-server"
	@echo "  make server-run         Build and run the server"
	@echo "  make server-test        Run Go tests"
	@echo "  make server-test-race   Run Go tests with the race detector"
	@echo "  make server-lint        Run go vet"
	@echo "  make server-fmt         Format Go sources with gofmt"
	@echo "  make android-build      Build the Android debug APK"
	@echo "  make android-test       Run Android unit tests"
	@echo "  make android-lint       Run Android lint"
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

android-build:
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

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

clean:
	rm -rf $(SERVER_DIR)/bin $(ANDROID_DIR)/build $(ANDROID_DIR)/app/build
	rm -f $(SERVER_DIR)/velin-server $(SERVER_DIR)/*.test coverage.out $(SERVER_DIR)/coverage.out
