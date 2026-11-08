#!/bin/bash
set -e
cd "$(dirname "$0")"

export JAVA_HOME="$(ls -d "$HOME"/.local/opt/jdk-17* | head -1)"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew installDebug
