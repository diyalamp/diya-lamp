#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${DIYALAMP_COORDINATION_TEST_URL:?Set a loopback insecure development CockroachDB JDBC URL targeting defaultdb}"
sh build.sh
DRIVER=build/deps/postgresql-42.7.14.jar
HASH=73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c
if [ ! -f "$DRIVER" ]; then
  curl --fail --location --proto '=https' --tlsv1.2 https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar -o "$DRIVER.tmp"
  printf '%s  %s\n' "$HASH" "$DRIVER.tmp" | shasum -a 256 -c -
  mv "$DRIVER.tmp" "$DRIVER"
fi
printf '%s  %s\n' "$HASH" "$DRIVER" | shasum -a 256 -c -
mkdir -p build/test-classes
javac --release 21 -cp build/classes -d build/test-classes src/test/java/com/diyalamp/coordination/JdbcTaskCoordinatorIntegrationTest.java
java -cp "build/classes:build/test-classes:$DRIVER" com.diyalamp.coordination.JdbcTaskCoordinatorIntegrationTest "$DIYALAMP_COORDINATION_TEST_URL"
