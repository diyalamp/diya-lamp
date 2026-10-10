#!/bin/sh
set -eu
mkdir -p build/classes
javac --release 21 -d build/classes src/main/java/com/diyalamp/Main.java
jar --create --file build/diya-lamp.jar --main-class com.diyalamp.Main -C build/classes .
