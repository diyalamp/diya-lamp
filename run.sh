#!/bin/sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN=$(command -v java)
else
  echo 'Install JDK 21 or newer and set JAVA_HOME, then try again.' >&2
  exit 2
fi
if [ -f "$APP_DIR/diya-lamp.jar" ]; then
  JAR="$APP_DIR/diya-lamp.jar"
else
  JAR="$APP_DIR/build/diya-lamp.jar"
fi
if [ ! -f "$JAR" ]; then
  echo 'Build the application with sh build.sh before running it.' >&2
  exit 2
fi
exec "$JAVA_BIN" -jar "$JAR" "$@"
