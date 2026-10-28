#!/bin/bash
set -e
export JAVA_HOME="/Users/rajesh/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home"
export PATH="/Users/rajesh/tools/apache-maven-3.9.9/bin:$JAVA_HOME/bin:$PATH"

echo "========================================================"
echo " Starting Spring AI RAG Pipeline (Java 25)"
echo "========================================================"
java -version
echo "========================================================"

./mvnw spring-boot:run
