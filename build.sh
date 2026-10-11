#!/bin/sh
set -eu
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/sources.txt
javac --release 21 -d build/classes @build/sources.txt
jar --create --file build/diya-lamp.jar --main-class com.diyalamp.Main -C build/classes .
