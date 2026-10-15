# Third-party notices

Gson 2.14.0 is used for JSON parsing, copyright Google Inc., licensed under Apache License 2.0.

- Project: https://github.com/google/gson
- License: [bundled Apache License 2.0](licenses/Gson-LICENSE.txt)

The build pins the artifact SHA-256 and downloads it into the ignored build directory. Distributions must retain Gson's bundled license and notices.

## Optional database integration test

PostgreSQL JDBC 42.7.14 is downloaded from Maven Central into the ignored build
directory by `coordination-test.sh`, with a pinned SHA-256. It is not bundled in
the CLI distribution. The driver and its dependencies retain the license files
inside the upstream JAR, including `META-INF/LICENSE` and `META-INF/licenses/`.
Project and upstream license: https://github.com/pgjdbc/pgjdbc
