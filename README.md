**English** · [中文](README.zh-CN.md)

# coaming

A GraalVM Native Image OCI container runtime, written in Java + Panama FFM and Rust. Implements the [OCI Runtime Specification](https://github.com/opencontainers/runtime-spec).

## Build

```sh
mvn test
mvn -Pnative -DskipTests package
```

The executable is `target/coaming`.

## Run

The bundle contains `config.json` and the rootfs it names. `--root` is the state directory and defaults to `/run/coaming`.

```sh
coaming --version
coaming --root /run/coaming create --bundle /path/to/bundle demo
coaming --root /run/coaming start demo
coaming --root /run/coaming state demo
coaming --root /run/coaming kill demo TERM
coaming --root /run/coaming delete demo
```

`coaming --version` exits 0. The first line is `coaming version 0.1.0`.

## Boundary

cgroup v2 only. `coaming features` reports `cgroup.v1` false and `systemdUser` false. A non-empty `process.selinuxLabel` is rejected.

containerd sets `BinaryName` to this binary and keeps the shim `containerd-shim-runc-v2`. cri-o sets `runtime_path` to this binary.

## Comparison

Three samples on one cgroup v2 machine. The bundle is the same for both. The clock starts at `create` and stops when `start` returns, which is after `/bin/sleep` has been entered. RSS is that process's `VmRSS`.

| runtime | sample 1 | sample 2 | sample 3 |
| --- | --- | --- | --- |
| coaming 0.1.0 | 47 ms, 1308 KiB | 49 ms, 1312 KiB | 47 ms, 1304 KiB |
| runc 1.4.2 | 23 ms, 1304 KiB | 25 ms, 1300 KiB | 29 ms, 1300 KiB |


## Install

`dev/package.sh` packs `target/coaming` into `coaming-<version>-linux-amd64.tar.gz`. The version is `Coaming.VERSION`.

Point containerd's `BinaryName` at the unpacked `coaming` and restart that containerd. The shim stays `containerd-shim-runc-v2`. Point cri-o's `runtime_path` at the same binary and restart crio.

To roll back, point `BinaryName` or `runtime_path` at the previous runtime and restart only that service.


## License

[Apache-2.0](LICENSE)
