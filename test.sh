#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh build.sh
rm -rf build/test-classes
mkdir -p build/test-classes
find src/test/java -name '*.java' | sort > build/test-sources.txt
javac --release 21 -cp build/classes:build/deps/gson-2.14.0.jar -d build/test-classes @build/test-sources.txt
java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.workflow.WorkflowTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.workflow.SessionStoreTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.notion.NotionClientTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.ai.CodexReviewerTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.notion.NotionPublisherTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.workflow.ConnectedWorkflowTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.ai.CodexProcessTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.service.ReviewAccessTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.ai.DocumentReviewAgentTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.ai.EvidenceReviewTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.ai.GroundedDocumentReviewTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.ai.ReviewJsonTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.ai.SemanticSupportEvaluationTest

java -cp build/classes:build/test-classes:build/deps/gson-2.14.0.jar com.diyalamp.coordination.CoordinationValidationTest
