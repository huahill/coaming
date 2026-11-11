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

## License

[Apache-2.0](LICENSE)
