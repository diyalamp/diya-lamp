#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh test.sh
RELEASE_DIR=build/release/diyalamp
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/deps" "$RELEASE_DIR/licenses"
cp build/diya-lamp.jar "$RELEASE_DIR/"
cp build/deps/gson-2.14.0.jar "$RELEASE_DIR/deps/"
cp run.sh diyalamp.command README.md THIRD_PARTY_NOTICES.md "$RELEASE_DIR/"
cp licenses/Gson-LICENSE.txt "$RELEASE_DIR/licenses/"
cp -R examples "$RELEASE_DIR/"
chmod +x "$RELEASE_DIR/run.sh" "$RELEASE_DIR/diyalamp.command"
rm -f build/release/diyalamp.zip
(cd build/release && zip -q -r -X diyalamp.zip diyalamp)
printf 'Package ready: build/release/diyalamp.zip\n'
