#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
binary=$root/target/coaming
launcher=$root/native/nsexec/target/release/coaming
if [ ! -x "$binary" ]; then
  echo "native image is missing: $binary" >&2
  exit 1
fi
if [ ! -x "$launcher" ]; then
  echo "launcher is missing: $launcher" >&2
  exit 1
fi
version=$(sed -n 's/.*static final String VERSION = "\([^"]*\)".*/\1/p' "$root/src/main/java/io/github/huahill/coaming/Coaming.java")
if [ -z "$version" ]; then
  echo "Coaming.VERSION was not found" >&2
  exit 1
fi
stage=$(mktemp -d)
cp "$launcher" "$stage/coaming"
cp "$binary" "$stage/coaming-runtime"
chmod 755 "$stage/coaming" "$stage/coaming-runtime"
archive=$root/coaming-$version-linux-amd64.tar.gz
tar -C "$stage" -czf "$archive" coaming coaming-runtime
rm -r "$stage"
echo "$archive"
