#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec "$ROOT/tools/gradle.sh" --console=plain :opstate-core:check :opstate-core:demo :opstate-core:meetingDemo "$@"
