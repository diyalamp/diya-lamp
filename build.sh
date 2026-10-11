#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/deps
DEP=build/deps/gson-2.14.0.jar
if [ ! -f "$DEP" ]; then
  curl --fail --location --proto '=https' --tlsv1.2 https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar -o "$DEP.tmp"
  mv "$DEP.tmp" "$DEP"
fi
printf '%s  %s\n' 2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f "$DEP" | shasum -a 256 -c -
rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/sources.txt
javac --release 21 -cp "$DEP" -d build/classes @build/sources.txt
printf 'Class-Path: deps/gson-2.14.0.jar\n' > build/manifest.mf
jar --create --file build/diya-lamp.jar --main-class com.diyalamp.Main --manifest build/manifest.mf -C build/classes .
