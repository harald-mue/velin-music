#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <public-base-url> <origin-base-url>" >&2
  echo "Example: $0 https://music.example.com http://192.0.2.10:8081" >&2
  exit 2
fi

for command in curl python3 sha256sum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 2
  fi
done

public_base=${1%/}
origin_base=${2%/}
temporary_directory=$(mktemp -d)
umask 077
cleanup() {
  rm -rf "$temporary_directory"
}
trap cleanup EXIT

read -r -s -p "One-time pairing code for a temporary curl-debug device: " pairing_code
echo
if [[ -z "$pairing_code" ]]; then
  echo "Pairing code must not be empty." >&2
  exit 2
fi

pair_body=$(PAIRING_CODE="$pairing_code" python3 -c \
  'import json, os; print(json.dumps({"code": os.environ["PAIRING_CODE"]}))')
unset pairing_code

curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --data "$pair_body" \
  "$public_base/api/v1/pair" >"$temporary_directory/pair.json"
unset pair_body

token=$(python3 -c \
  'import json, sys; value=json.load(open(sys.argv[1])); print(value["token"])' \
  "$temporary_directory/pair.json")
if [[ -z "$token" ]]; then
  echo "Pair response did not contain a token." >&2
  exit 1
fi

printf 'header = "Authorization: Bearer %s"\n' "$token" >"$temporary_directory/auth.curl"
printf 'header = "Accept-Encoding: identity"\n' >>"$temporary_directory/auth.curl"
unset token

curl --fail-with-body --silent --show-error \
  --config "$temporary_directory/auth.curl" \
  "$public_base/api/v1/tracks?limit=1" >"$temporary_directory/tracks.json"

track_id=$(python3 -c '
import json, sys
value=json.load(open(sys.argv[1]))
items=value.get("items", [])
if not items:
    raise SystemExit("The library returned no tracks.")
print(items[0]["id"])
' "$temporary_directory/tracks.json")

echo "Testing track ID: $track_id"

test_range() {
  local label=$1
  local base=$2
  local headers="$temporary_directory/$label.headers"
  local body="$temporary_directory/$label.bin"
  local result

  result=$(curl --silent --show-error \
    --config "$temporary_directory/auth.curl" \
    --range 0-1048575 \
    --dump-header "$headers" \
    --output "$body" \
    --write-out 'status=%{http_code} bytes=%{size_download} type=%{content_type} time=%{time_total}s' \
    "$base/api/v1/tracks/$track_id/stream")

  echo "$label: $result"
  grep -iE '^(content-range|accept-ranges|content-length|content-type|location|cf-ray|server):' "$headers" || true
  sha256sum "$body" | sed "s#$temporary_directory/$label.bin#$label.bin#"
  echo
}

test_range origin "$origin_base"
test_range public "$public_base"

if cmp -s "$temporary_directory/origin.bin" "$temporary_directory/public.bin"; then
  echo "Result: origin and public response bytes match."
else
  echo "Result: origin and public response bytes differ. Inspect the statuses and headers above."
fi

echo "Revoke the temporary curl-debug device in the Velin admin UI when finished."
