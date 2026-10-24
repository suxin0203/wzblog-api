#!/usr/bin/env bash
# Source this file from project root: source scripts/env.sh
set -a
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$ROOT_DIR/.env" ] && . "$ROOT_DIR/.env"
set +a

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Microsoft/jdk-21.0.12.8-hotspot}"
export PATH="$JAVA_HOME/bin:$ROOT_DIR/tools/apache-maven-3.9.16/bin:$PATH"
export MVN="$ROOT_DIR/tools/apache-maven-3.9.16/bin/mvn"

cd "$ROOT_DIR"