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
coaming --root /run/coaming create --bundle /path/to/bundle demo
coaming --root /run/coaming start demo
coaming --root /run/coaming state demo
coaming --root /run/coaming kill demo TERM
coaming --root /run/coaming delete demo
```

## License

[Apache-2.0](LICENSE)
