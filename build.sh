#!/bin/sh
set -eu
mkdir -p build/classes
javac --release 21 -d build/classes src/main/java/io/diyalamp/Main.java
jar --create --file build/diya-lamp.jar --main-class io.diyalamp.Main -C build/classes .
