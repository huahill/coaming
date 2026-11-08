[English](README.md) · **中文**

# coaming

GraalVM Native Image OCI 容器运行时，使用 Java + Panama FFM 和 Rust 编写。实现 [OCI Runtime Specification](https://github.com/opencontainers/runtime-spec)。

## 构建

```sh
mvn test
mvn -Pnative -DskipTests package
```

可执行文件是 `target/coaming`。

## 运行

bundle 里放 `config.json` 和它指定的 rootfs。`--root` 是状态目录，默认 `/run/coaming`。

```sh
coaming --root /run/coaming create --bundle /path/to/bundle demo
coaming --root /run/coaming start demo
coaming --root /run/coaming state demo
coaming --root /run/coaming kill demo TERM
coaming --root /run/coaming delete demo
```

## 许可证

[Apache-2.0](LICENSE)
