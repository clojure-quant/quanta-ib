#!/usr/bin/env bash
# Regenerate Clojure bindings from vendored IB .proto files (protojure).
# Requires: protoc, protoc-gen-clojure on PATH.
# Note: vendored protos have proto3 `optional` stripped (protojure plugin limitation).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p src
protoc --proto_path=resources/proto --clojure_out=src resources/proto/*.proto
echo "Generated into $ROOT/src"
