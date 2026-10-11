#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh build.sh
rm -rf build/test-classes
mkdir -p build/test-classes
find src/test/java -name '*.java' | sort > build/test-sources.txt
javac --release 21 -cp build/classes -d build/test-classes @build/test-sources.txt
java -cp build/classes:build/test-classes com.diyalamp.workflow.WorkflowTest
