#!/bin/sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '\n🪔 diyalamp: Defensible writing with AI in Notion.\n\n'
printf 'Existing review session path: '
IFS= read -r REVIEW_PATH
printf 'Claude model ID from your API account: '
IFS= read -r REVIEW_MODEL
sh "$APP_DIR/run.sh" guide "$REVIEW_PATH" "$REVIEW_MODEL"
