#!/usr/bin/env bash
set -euo pipefail

tooling_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
case "${1:-}" in
  debug) tasks=(testDebugUnitTest lintDebug assembleDebug) ;;
  release) tasks=(testDebugUnitTest lintRelease assembleRelease) ;;
  *) echo 'Usage: verify_android.sh debug|release' >&2; exit 2 ;;
esac

# Hosted runners have limited memory. Keep lint/packaging in separate JVMs and
# avoid retaining another compiler heap alongside the Gradle and test heaps.
gradle=(./gradlew -I "$tooling_dir/test-runtime.gradle"
  --no-daemon --no-configuration-cache --no-parallel --max-workers=2 --stacktrace
  '-Dorg.gradle.jvmargs=-Xmx4096m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
  -Pkotlin.compiler.execution.strategy=in-process)
for task in "${tasks[@]}"; do
  "${gradle[@]}" "$task"
done
