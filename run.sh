#!/bin/bash
set -e

# Always run from the project root directory
cd "$(dirname "$0")"

# 1. Resolve JAVA_HOME (preserve existing or fallback to Loom 25)
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    if [ -d "/Users/rajesh/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home" ]; then
        export JAVA_HOME="/Users/rajesh/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home"
    elif command -v /usr/libexec/java_home &> /dev/null; then
        export JAVA_HOME=$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)
    fi
fi

if [ -n "$JAVA_HOME" ] && [ -d "$JAVA_HOME/bin" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -d "/Users/rajesh/tools/apache-maven-3.9.9/bin" ]; then
    export PATH="/Users/rajesh/tools/apache-maven-3.9.9/bin:$PATH"
fi

# 2. Port resolution with auto-detection of conflicts
TARGET_PORT="${PORT:-${1:-8080}}"

echo "========================================================"
echo " Starting Enterprise Spring AI RAG Pipeline (Java 25)"
echo "========================================================"
java -version 2>&1 | head -n 3
echo "========================================================"

# Check if target port is already in use
PORT_OCCUPIED_PID=$(lsof -ti :$TARGET_PORT 2>/dev/null || true)
if [ -n "$PORT_OCCUPIED_PID" ]; then
    echo "⚠️ Port $TARGET_PORT is currently in use (PID: $(echo $PORT_OCCUPIED_PID | tr '\n' ' '))."
    # Find next available port
    NEW_PORT=$TARGET_PORT
    while [ -n "$(lsof -ti :$NEW_PORT 2>/dev/null || true)" ]; do
        NEW_PORT=$((NEW_PORT + 1))
    done
    echo "💡 Automatically switching to available port: $NEW_PORT"
    echo "   (You can also specify a custom port: PORT=8085 ./run.sh or ./run.sh 8085)"
    TARGET_PORT=$NEW_PORT
fi

# 3. Pre-flight check: Ollama service availability
if curl -s -m 2 http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo "✅ Ollama detected running at http://localhost:11434"
else
    echo "⚠️ Notice: Ollama does not seem to be responding at http://localhost:11434"
    echo "   Ensure Ollama is started ('ollama serve') for local vector embeddings and LLM reasoning."
fi

echo "🚀 Launching Spring Boot server on port $TARGET_PORT..."
echo "📂 Web Dashboard will be available at: http://localhost:$TARGET_PORT"
echo "========================================================"

./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=$TARGET_PORT"
