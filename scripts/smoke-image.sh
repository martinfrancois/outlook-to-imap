#!/usr/bin/env bash
# Builds the container image and checks that it starts, answers /health, reports a missing login
# with the fix, and stops cleanly. Usage: scripts/smoke-image.sh [docker|podman] [image-tag]
set -euo pipefail

engine=${1:-docker}
image=${2:-outlook-to-imap:smoke}
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
name="outlook-to-imap-smoke-$$"
cleanup() {
  "$engine" rm -f "$name" >/dev/null 2>&1 || true
  "$engine" volume rm -f "$name" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

build_args=()
if [ "$engine" = podman ]; then
  # Podman builds OCI images by default, and the OCI format has no HEALTHCHECK.
  build_args+=(--format docker)
fi
"$engine" build "${build_args[@]}" -t "$image" "$root"

printf 'app-password\n' > "$work/fastmail-password"
cat > "$work/config.yaml" <<'YAML'
microsoft:
  clientId: 00000000-0000-0000-0000-000000000000
destinations:
  fastmail:
    host: 127.0.0.1
    username: me@fastmail.example
    passwordFile: fastmail-password
accounts:
  - name: hotmail
    email: me@hotmail.example
    folders:
      - source: junkemail
        destination: fastmail
        folder: Outlook Junk
YAML
chmod 755 "$work"
chmod 644 "$work"/*
"$engine" volume create "$name" >/dev/null

"$engine" run --rm "$image" --version

set +e
output=$("$engine" run --rm -v "$work:/config:ro,z" -v "$name:/data" "$image" once 2>&1)
status=$?
set -e
echo "$output"
if [ "$status" -ne 1 ] || ! grep -q 'outlook-to-imap authorize hotmail' <<<"$output"; then
  echo "once should exit 1 and name the authorize command, got exit $status" >&2
  exit 1
fi

"$engine" run -d --name "$name" -v "$work:/config:ro,z" -v "$name:/data" "$image" >/dev/null
for _ in $(seq 1 60); do
  if "$engine" exec "$name" curl -fsS -o /dev/null http://127.0.0.1:8080/health 2>/dev/null; then
    healthy=yes
    break
  fi
  sleep 1
done
if [ "${healthy:-}" != yes ]; then
  "$engine" logs "$name" >&2
  echo "/health never answered 200" >&2
  exit 1
fi

start=$(date +%s)
"$engine" stop -t 30 "$name" >/dev/null
elapsed=$(( $(date +%s) - start ))
if [ "$elapsed" -ge 30 ]; then
  echo "the container ignored SIGTERM and was killed after ${elapsed}s" >&2
  exit 1
fi
echo "image ok: once, /health and a clean stop in ${elapsed}s"
