#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
binary=$root/target/coaming
if [ ! -x "$binary" ]; then
  echo "native image is missing: $binary" >&2
  exit 1
fi
version=$(sed -n 's/.*static final String VERSION = "\([^"]*\)".*/\1/p' "$root/src/main/java/io/github/huahill/coaming/Coaming.java")
if [ -z "$version" ]; then
  echo "Coaming.VERSION was not found" >&2
  exit 1
fi
stage=$(mktemp -d)
cp "$binary" "$stage/coaming"
chmod 755 "$stage/coaming"
machine=$(uname -m)
case "$machine" in
  x86_64) arch=amd64 ;;
  aarch64) arch=arm64 ;;
  *)
    echo "unsupported architecture: $machine" >&2
    exit 1
    ;;
esac
archive=$root/coaming-$version-linux-$arch.tar.gz
tar -C "$stage" -czf "$archive" coaming
rm -r "$stage"
echo "$archive"
