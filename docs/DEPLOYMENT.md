# Velin Docker deployment

The step-by-step **developer** procedure for configuring `.env`, building `velin-server:local`, copying the image to a target host, starting Compose, adding `/music`, and updating the container is in the repository [`README.md`](../README.md) (section **Docker (developer)**).

This page records the deployment contract: scratch image, bind mounts, permissions, reverse proxy, and backups. The image is a **static Go binary in `scratch`**: no shell, no package manager, and no CGO. SQLite uses the pure-Go `modernc.org/sqlite` driver, so the runtime does not need libc.

The Compose file also mounts a small `tmpfs` at `/tmp` because the image root is read-only and `scratch` has no writable `/tmp`.

## Layout

| Host (defaults) | Container | Purpose |
| --- | --- | --- |
| `./data` | `/data` (read-write) | Database and artwork cache. Mode `0700`. |
| `./music` (or your library path) | `/music` (read-only) | FLAC/MP3 library. Never written by Velin. |
| TCP `8080` | `:8080` | Admin UI and API. |

The Go process still registers `/admin/*` and `/api/*`. Put a reverse proxy in front if you need TLS or a path prefix such as `/velin`.

## Requirements

- Docker Engine with Compose v2
- Host directories for data and music
- A numeric UID/GID that can write `VELIN_DATA_DIR` and **read** the music tree

Scratch has no `adduser`. Identity is only a number, default `1000:1000`.

## First start

From the repository root:

```sh
cp .env.example .env
# Edit VELIN_MUSIC_DIR, VELIN_UID, and VELIN_GID in .env

mkdir -p data
chmod 700 data
# If you keep the default music path:
mkdir -p music

docker compose up --build -d
```

Open `http://127.0.0.1:8080/admin/` and complete administrator setup.

Then in **Library roots**, add the container path `/music` (not the host path). Start a scan. Add more bind mounts only if you have additional roots; each mount needs its own container path.

Confirm the process is up:

```sh
curl -sS http://127.0.0.1:8080/api/v1/status
```

Expected JSON includes `"status":"ok"`. There is no in-image `HEALTHCHECK`: `scratch` has no curl/wget, and the binary has no health subcommand.

## `.env` values

Compose reads `.env` next to `docker-compose.yml`. Do not commit `.env`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `VELIN_UID` / `VELIN_GID` | `1000` | Container user. Must own `./data` and be able to read music files. |
| `VELIN_DATA_DIR` | `./data` | Host path mounted at `/data`. |
| `VELIN_MUSIC_DIR` | `./music` | Host music tree mounted at `/music:ro`. |
| `VELIN_PORT` | `8080` | Host port. |
| `VELIN_VERSION` | `dev` | Status endpoint version string. |
| `VELIN_PUBLIC_URL` | empty | Fallback public base for pairing QR payloads created without `server_url`. |
| `VELIN_SECURE_COOKIES` | `false` | Set `true` when HTTPS terminates at a reverse proxy. |
| `VELIN_SCAN_ON_STARTUP` | `false` | Full-library scan after startup. |
| `VELIN_SCAN_INTERVAL` | empty | Periodic scan duration (for example `6h`). Empty disables it. |

On Linux, `id -u` and `id -g` are the usual UID/GID for a personal library.

## Permissions

- Startup **tightens** an existing data directory to mode `0700` when the process owns it. Docker bind mounts that start as `0755` therefore become private on first run.
- If the data directory is owned by root and the container runs as `1000`, startup fails. Fix with `chown -R 1000:1000 data` (use your `VELIN_UID`).
- Music is read-only. Files must be readable by `VELIN_UID` (world-readable files, or a matching group). Velin still rejects symlink roots and skips symlinked files during discovery.
- Do not mount the music tree at `/data`.

## Extra music directories

Add another read-only volume and register that container path as a second library root:

```yaml
volumes:
  - "${VELIN_DATA_DIR:-./data}:/data"
  - "${VELIN_MUSIC_DIR:-./music}:/music:ro"
  - /mnt/archive:/archive:ro
```

Then add `/archive` in the admin UI.

## Reverse proxy and pairing

- Terminate TLS at the proxy. Set `VELIN_SECURE_COOKIES=true`. Velin does not trust `X-Forwarded-*` headers.
- If the proxy exposes a path prefix, forward both `<prefix>/admin/*` and `<prefix>/api/*` after stripping the same prefix. See [`API.md`](API.md) and ADR-023.
- Pairing `server_url` should be the URL the Android device can reach (LAN IP, hostname, or prefix-preserving HTTPS URL). The admin form defaults from the browser address bar.

### Cloudflare Tunnel and playback diagnostics

Cloudflare Tunnel can proxy downloads and byte-range requests; do not assume that the tunnel itself is incompatible with audio. Browse/search succeeding proves only that small authenticated JSON requests work. Playback additionally needs an authenticated `GET /api/v1/tracks/{id}/stream`, a valid media response, and byte-range behavior.

Use the same server, image, APK, and track for an A/B test:

1. Pair through `http://<lan-ip>:<VELIN_PORT>` and play the track.
2. Pair through the final public HTTPS URL and play the same track.
3. If behavior differs, compare the authenticated response bytes rather than changing proxy settings blindly.

If LAN playback fails too, investigate the container mount, file identity, Android service, and media parser before Cloudflare. If only the public URL fails, create a temporary device named `curl-debug` and copy its one-time pairing code. From a trusted machine with this repository, run:

```sh
scripts/debug-stream.sh https://music.example.com http://192.0.2.10:8081
```

Use the final public base URL, including any path prefix, and the directly reachable origin base. The script prompts without echo for the one-time code, obtains a temporary token, selects one track, requests the same first MiB from both URLs, and compares hashes. It keeps credentials in a mode-`0600` temporary directory, removes them on exit, and never prints the token. Revoke `curl-debug` in the admin UI afterward.

Both responses should normally have:

- status `206 Partial Content`;
- `Content-Range: bytes 0-1048575/<size>`;
- `Accept-Ranges: bytes`;
- `Content-Type: audio/flac` or `audio/mpeg`;
- exactly 1,048,576 response bytes with matching hashes.

Interpret differences before changing tunnel settings:

| Public result | Likely boundary |
| --- | --- |
| `301`/`302` | Wrong public base or redirect rule. Android deliberately refuses stream redirects so its bearer token cannot leak. Pair with the final URL. |
| `401` | Authorization did not reach Velin or the token/device was revoked. |
| `403` or HTML | Cloudflare Access/WAF/Bot challenge intercepted `/api/*`; native clients cannot solve browser challenges. |
| `404` | Public path prefix and tunnel ingress disagree. Both `<prefix>/admin/*` and `<prefix>/api/*` must reach Velin. |
| `502`/`504`/`524` | Tunnel-to-origin connection or timeout problem; inspect `cloudflared` logs. |
| `200` without `Content-Range` | The Range header was removed or ignored. Initial playback may work, but seeking and extractor reads can fail. |
| Correct `206` and matching bytes | The tunnel transport works for the sample. Capture Android `adb logcat` and investigate the device/client path instead of changing Cloudflare blindly. |

Do not enable HTTP/1.1-only mode, disable chunked encoding, or relax authentication without evidence from this comparison. A Cloudflare Access policy, if used, must allow native `/api/*` requests without an interactive HTML login while Velin still enforces its bearer token.

## What this image cannot do

- No shell: `docker compose exec velin sh` will fail. Inspect logs with `docker compose logs -f`.
- No `HEALTHCHECK` helper inside the image.
- No packaged CA certificates or timezone database. The server is an inbound HTTP listener; logs use the process timezone (normally UTC).
- No published registry image yet. Operators build locally with Compose.

## Commands

```sh
docker compose up --build -d
docker compose logs -f
docker compose down
```

`make docker-build` and `make docker-up` wrap the same Compose commands.

## Backup

Stop or briefly quiesce writes, then copy the host data directory. The important files are `velin.db` plus SQLite `-wal`/`-shm` if present, and the artwork cache under the same directory. Music files are not stored in `/data`; back them up from the host library as usual.
