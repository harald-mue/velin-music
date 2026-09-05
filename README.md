# Velin

*Your music. Your network.*

Repository: https://github.com/harald-mue/velin-music

Velin is a self-hosted music system for large personal libraries. The Go server indexes music from local filesystem roots, stores searchable metadata, serves artwork, and streams original audio. The native Android application will connect through QR-based device pairing and provide a focused browsing and playback experience.

Velin is **FLAC-first with first-class MP3 support**. Both formats are read-only from Velin's perspective and are streamed in their original form; version one does not transcode.

## Status

The repository contains a compilable Go server and a native Kotlin/Jetpack Compose Android client. The server provides authenticated library browsing, search, artwork, original-format streaming, administration, safe incremental indexing, optional startup scans, and scheduled scans. The Android app provides QR/manual pairing with Android Keystore-backed credentials and authenticated Home, Queue, and Library screens (search lives in Library). The Queue tab can Clear the live list and Save/Load one device-private slot of opaque track IDs. Playback uses a shared Media3 `MediaLibraryService` for the phone UI, system controls, and Android Auto media browsing.

Current state and the next handoff are maintained in [`docs/PROGRESS.md`](docs/PROGRESS.md).

## Planned architecture

- A single Go executable and data directory.
- `net/http`, SQLite with FTS5, schema migrations, and server-rendered administration pages.
- Incremental, bounded indexing of configured roots, including FLAC and MP3 metadata and embedded artwork.
- Original HTTP byte-range streaming without transcoding.
- A native Kotlin/Jetpack Compose Android client using AndroidX Media3.
- Short-lived one-time QR pairing codes exchanged for revocable device tokens.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Docker (developer)

This section is the developer procedure for building the image, configuring it, copying it to a target host, starting the container, and operating it. Reverse-proxy and security notes live in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

The runtime image is **scratch plus one static binary**. There is no shell, no package manager, and no CGO. SQLite is `modernc.org/sqlite`. You do not need a local Go toolchain to build the image; Docker pulls `golang:1.26-bookworm` for the build stage.

All Docker commands below must run from the **repository root** (the directory that contains `Dockerfile` and `docker-compose.yml`).

### Files

| File | Role |
| --- | --- |
| `Dockerfile` | Multi-stage build: compile `./cmd/velin-server` with `CGO_ENABLED=0`, copy `/velin-server` into `scratch`. |
| `.dockerignore` | Keeps Android, docs, `.git`, and local data out of the build context. |
| `docker-compose.yml` | Service `velin`, image name `velin-server:local`, bind mounts, ports, hardening. |
| `.env.example` | Template for Compose substitution. Copy to `.env`; never commit `.env`. |

Compose reads `.env` automatically when it is next to `docker-compose.yml`. Values in `.env` are **host-side** substitutions (`VELIN_UID`, host paths, published port). The process inside the container always uses `VELIN_DATA_DIR=/data` and listens on `:8080`.

### Image layout

| Host (defaults) | Container | Mode | Purpose |
| --- | --- | --- | --- |
| `./data` | `/data` | read-write | SQLite (`velin.db`) and artwork cache |
| `./music` or `VELIN_MUSIC_DIR` | `/music` | read-only | FLAC/MP3 library; Velin never writes it |
| host `VELIN_PORT` (default `8080`) | `:8080` | TCP | Admin UI and API |
| Compose `tmpfs` | `/tmp` | 64 MiB | Required because the container root filesystem is read-only |

Default container user is `1000:1000` (overridden by `VELIN_UID`/`VELIN_GID`). Scratch has no `adduser`; identity is only a number.

### Prerequisites

On the **build machine** and the **target host**:

- Docker Engine with Compose v2 (`docker compose version`)
- Permission to run Docker (membership in group `docker`, or root)
- A numeric UID/GID that can **write** the data directory and **read** the music tree

On the target host you also need the music directory already present (or created empty for a first test).

### 1. Configure (build machine or target)

```sh
cd /path/to/velin
cp .env.example .env
```

Edit `.env`. On Linux, fill UID/GID from the account that owns the library:

```sh
id -u    # → VELIN_UID
id -g    # → VELIN_GID
```

| Variable | Default | Set this to |
| --- | --- | --- |
| `VELIN_UID` / `VELIN_GID` | `1000` | Host user that owns `./data` and can read music |
| `VELIN_DATA_DIR` | `./data` | Host directory for the database (relative to the compose file, or absolute) |
| `VELIN_MUSIC_DIR` | `./music` | Host music tree; mounted at `/music:ro` |
| `VELIN_PORT` | `8080` | Host port published to container `8080` |
| `VELIN_VERSION` | `dev` | String returned by `GET /api/v1/status` |
| `VELIN_PUBLIC_URL` | empty | Optional public base for pairing QR payloads created without `server_url` |
| `VELIN_SECURE_COOKIES` | `false` | `true` when HTTPS terminates at a reverse proxy |
| `VELIN_SCAN_ON_STARTUP` | `false` | `true` to start a full-library scan after boot |
| `VELIN_SCAN_INTERVAL` | empty | Go duration such as `6h`; empty disables periodic scans |

Create the data directory before the first start. If you keep the default music path and it does not exist yet, create it too:

```sh
mkdir -p data
chmod 700 data
mkdir -p music   # skip if VELIN_MUSIC_DIR already points at a real library
```

If `VELIN_DATA_DIR` is owned by root while the container runs as `1000`, startup fails. Fix with `chown -R "$VELIN_UID:$VELIN_GID" data` using the values from `.env`.

Music files must be readable by `VELIN_UID` (mode `0644`/`0755`, or a matching group). Velin rejects symlink roots and skips symlinked files. Do not mount the music tree at `/data`.

### 2. Build the image

Either:

```sh
make docker-build
```

or:

```sh
docker compose build
```

Compose tags the image `velin-server:local`. The first build downloads `golang:1.26-bookworm`; later builds reuse the layer cache.

Confirm:

```sh
docker image inspect velin-server:local --format '{{.Size}} {{.Os}}/{{.Architecture}} {{.Config.User}} {{json .Config.Entrypoint}}'
```

Expect a few megabytes, `linux/<your-arch>`, user `1000:1000` (Dockerfile default; Compose still runs as `VELIN_UID:VELIN_GID`), entrypoint `["/velin-server"]`.

Rebuild after Go or Dockerfile changes with the same command. To force a clean compile:

```sh
docker compose build --no-cache
```

### 3. Start on the same machine (local check)

```sh
make docker-up
```

or:

```sh
docker compose up --build -d
```

`--build` rebuilds if the Dockerfile or server sources changed, then starts service `velin` in the background with `restart: unless-stopped`.

Check process and HTTP:

```sh
docker compose ps
docker compose logs -f velin
curl -sS http://127.0.0.1:8080/api/v1/status
```

Expected body includes `"name":"Velin"` and `"status":"ok"`. There is no image `HEALTHCHECK`: scratch has no curl, and the binary has no health subcommand.

Open `http://127.0.0.1:${VELIN_PORT}/admin/` (default port `8080`). Complete administrator setup on first visit.

In **Library roots**, add the **container** path `/music`, not the host path. Then start a scan.

`docker compose exec velin sh` will fail (no shell). Use logs only.

Stop / start / remove the container (data and music on the host are kept):

```sh
docker compose stop
docker compose start
docker compose down
```

`down` does not delete `./data` or the music bind mount.

### 4. Install on a target host

There is no published registry image. Use one of the two flows below. On the target, Docker and Compose v2 must already be installed.

#### Option A — clone and build on the target (simplest)

On the target:

```sh
git clone https://github.com/harald-mue/velin-music.git
cd velin-music
git checkout <commit-or-branch-you-tested>
```

Copy or recreate `.env` (do not commit it). Point `VELIN_MUSIC_DIR` at the target’s music directory (absolute path is fine, for example `/mnt/music`). Point `VELIN_DATA_DIR` at a private directory on the target, for example `./data` or `/var/lib/velin`. Set `VELIN_UID`/`VELIN_GID` to the target account that owns those paths (`id -u` / `id -g` on the target).

```sh
cp .env.example .env
# edit .env (music path, UID/GID, data path, port)
mkdir -p data
chmod 700 data
# if VELIN_DATA_DIR is not ./data, create and chmod 700 that host path instead
docker compose up --build -d
curl -sS http://127.0.0.1:8080/api/v1/status
```

If the target architecture differs from your laptop (for example `arm64` NAS vs `amd64` PC), this option is the right one: the image is built on the machine that will run it.

#### Option B — build here, copy the image, start on the other PC (for testing)

Use this when the other computer is also **linux/amd64** (this development machine is `amd64`). If the other PC is ARM (`aarch64` / Apple Silicon / many NAS boxes), use option A on that machine instead — a saved amd64 image will not run there.

**On this computer** (repository root):

```sh
make docker-save
```

That builds `velin-server:local` and writes `dist/velin-server-local.tar.gz`. Copy **three** things (the archive is gitignored; do not commit it):

| Copy | Why |
| --- | --- |
| `dist/velin-server-local.tar.gz` | The image |
| `docker-compose.yml` | Ports, mounts, user, read-only root |
| `.env.example` | Template; create a **new** `.env` on the other PC |

Example:

```sh
scp dist/velin-server-local.tar.gz docker-compose.yml .env.example user@other-pc:~/velin/
```

USB/rsync is fine too. Do not copy this machine’s `.env` unless UID, music path, and data path are identical on the other PC (they usually are not).

**On the other computer** (Docker Engine + Compose v2 already installed):

```sh
mkdir -p ~/velin
cd ~/velin
# after the three files are in this directory:
gzip -dc velin-server-local.tar.gz | docker load
docker image ls velin-server:local

cp .env.example .env
```

Edit `.env` **on that PC**:

- `VELIN_UID` / `VELIN_GID` → output of `id -u` and `id -g` there
- `VELIN_MUSIC_DIR` → absolute path to that PC’s music (must exist and be readable)
- `VELIN_DATA_DIR` → e.g. `./data` or `/var/lib/velin`
- `VELIN_PORT` → free host port, default `8080`

Then:

```sh
mkdir -p data
chmod 700 data
# if VELIN_MUSIC_DIR is ./music and you have no library yet: mkdir -p music
docker compose up -d
curl -sS http://127.0.0.1:8080/api/v1/status
docker compose logs -f velin
```

Do **not** use `docker compose up --build` on the other PC unless the Git tree is there and you intend to compile again. `up -d` starts the loaded `velin-server:local`.

Open `http://<other-pc-ip>:8080/admin/`, finish setup, add library root `/music`, start a scan.

### 5. After the container is running

1. Browse `http://<target-ip>:<VELIN_PORT>/admin/` and finish setup (or log in).
2. Add library root `/music`.
3. Start a scan; watch counters on the admin page or `docker compose logs -f velin`.
4. For Android pairing, set `server_url` to a URL the phone can reach (LAN IP or HTTPS name). The form defaults from the browser address bar. Set `VELIN_PUBLIC_URL` only if you create pairing codes without typing that URL.

To apply config changes in `.env` (port, UID, mounts, scan flags):

```sh
docker compose up -d
```

Compose recreates the container when the service definition changed. The image is reused unless you also rebuild.

To deploy a new binary with option A:

```sh
git pull
docker compose up --build -d
```

With option B: rebuild and `docker save` on the build machine, `docker load` on the target, then `docker compose up -d`.

### 6. Extra music directories

Edit `docker-compose.yml` volumes, then recreate:

```yaml
volumes:
  - "${VELIN_DATA_DIR:-./data}:/data"
  - "${VELIN_MUSIC_DIR:-./music}:/music:ro"
  - /mnt/archive:/archive:ro
```

```sh
docker compose up -d
```

Add `/archive` as a second library root in the admin UI.

### 7. Typical failures

| Symptom | Cause / fix |
| --- | --- |
| `data directory is accessible by other users` or permission errors on `/data` | Directory not owned by `VELIN_UID`, or not writable. `chown`/`chmod 700`. Startup will tighten mode to `0700` if the process owns the directory. |
| Scan finds nothing | Admin root was a host path. Use `/music`. Confirm the bind mount with `docker compose config`. |
| Cannot read files | Music not readable by `VELIN_UID`; or SELinux on Fedora — try `:ro,Z` on the music volume. |
| Admin cookies not `Secure` behind HTTPS | Set `VELIN_SECURE_COOKIES=true`. Velin does not trust `X-Forwarded-*`. |
| `exec` / `sh` fails | Expected. Use `docker compose logs`. |
| Wrong CPU architecture after `docker load` | Image was saved on another arch. Use option A on the target. |

Backup: copy the host data directory (`velin.db`, `-wal`/`-shm` if present, artwork cache). Music stays in the host library, not in `/data`.

## Repository layout

```text
AGENT.md             Persistent engineering instructions
server/              Go server
android/             Native Kotlin/Jetpack Compose client
server/internal/admin/ Embedded administration templates and assets
docs/                Architecture, API, security, design, and handoff documents
Makefile             Development commands
Dockerfile           Multi-stage scratch image for the server
docker-compose.yml   Bind-mounted music and data volumes
.env.example         Compose host-side configuration template
docs/DEPLOYMENT.md   Container contract, reverse proxy, backups
```

## Development prerequisites

- Go 1.23.2 or newer.
- JDK 17, Android SDK Platform 37.0, and Build-Tools 36.0.0 for Android development.

No Node.js runtime, system Gradle installation, CGO toolchain, or external service is required. The Android build uses the checked-in Gradle Wrapper.

## Development commands

```sh
make help           # list available targets
make server-build   # build server/bin/velin-server
make server-run     # build and run the server
make server-test    # run Go tests
make server-test-race
make server-lint    # go vet
make server-fmt     # format Go sources
make android-build  # build the debug APK
make android-test   # run Android unit tests
make android-lint   # run Android lint
make test           # run server and Android unit tests
make docker-build   # build the scratch server image
make docker-save    # build and write dist/velin-server-local.tar.gz for another host
make docker-up      # build and start Compose
make clean          # remove build artifacts
```

Commands are intentionally only added when they perform real validation. Read [`AGENT.md`](AGENT.md) before making changes.

## License

Velin is released under the MIT License; see [`LICENSE`](LICENSE).
