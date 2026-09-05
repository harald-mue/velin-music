# Velin server: static Go binary in a scratch image.
# Build from the repository root: docker compose build

FROM golang:1.26-bookworm AS build

WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download

COPY server/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/velin-server ./cmd/velin-server

FROM scratch

COPY --from=build /out/velin-server /velin-server

EXPOSE 8080
VOLUME ["/data"]

ENV VELIN_HTTP_ADDR=:8080 \
    VELIN_DATA_DIR=/data \
    VELIN_VERSION=dev

USER 1000:1000
ENTRYPOINT ["/velin-server"]
