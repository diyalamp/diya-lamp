#!/bin/sh
set -eu
cd "$(dirname "$0")"
SMOKE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/diyalamp-package.XXXXXX")
trap 'rm -rf "$SMOKE_DIR"' EXIT HUP INT TERM
mkdir -p "$SMOKE_DIR/application with spaces"
unzip -q build/release/diyalamp.zip -d "$SMOKE_DIR/application with spaces"
SMOKE_APP="$SMOKE_DIR/application with spaces/diyalamp"
SESSION="$SMOKE_DIR/demo.review.properties"
sh "$SMOKE_APP/run.sh" start "$SMOKE_APP/examples/onboarding/draft.txt" "$SESSION" >/dev/null
printf 'Synthetic manually supplied phase result.\n' > "$SMOKE_DIR/result.txt"
for phase in 1 2 3 4 5; do
  sh "$SMOKE_APP/run.sh" record "$SESSION" "$SMOKE_DIR/result.txt" >/dev/null
done
sh "$SMOKE_APP/run.sh" approve "$SESSION" > "$SMOKE_DIR/status.txt"
if ! grep -q 'State: READY' "$SMOKE_DIR/status.txt"; then
  echo 'Packaged workflow did not reach Ready.' >&2; exit 1
fi
sh "$SMOKE_APP/run.sh" export "$SESSION" "$SMOKE_DIR/report.md" >/dev/null
if ! grep -q 'Source fingerprint:' "$SMOKE_DIR/report.md"; then
  echo 'Packaged report is incomplete.' >&2; exit 1
fi
printf 'PASS: unpacked distribution runs from a path with spaces through five phases, approval, and export.\n'
