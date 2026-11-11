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
coaming --version
coaming --root /run/coaming create --bundle /path/to/bundle demo
coaming --root /run/coaming start demo
coaming --root /run/coaming state demo
coaming --root /run/coaming kill demo TERM
coaming --root /run/coaming delete demo
```

`coaming --version` 退出码为 0。第一行是 `coaming version 0.1.0`。

## 边界

只使用 cgroup v2。`coaming features` 里 `cgroup.v1` 为 false，`systemdUser` 为 false。非空的 `process.selinuxLabel` 会被拒绝。

containerd 把 `BinaryName` 指到这个二进制，shim 仍是 `containerd-shim-runc-v2`。cri-o 把 `runtime_path` 指到这个二进制。

## 对比

同一台 cgroup v2 机器、同一份 bundle，各采样 3 次。计时从 `create` 开始，到 `start` 返回结束，此时 `/bin/sleep` 已经进入。RSS 是该进程的 `VmRSS`。

| 运行时 | 第 1 次 | 第 2 次 | 第 3 次 |
| --- | --- | --- | --- |
| coaming 0.1.0 | 23 ms，2348 KiB | 32 ms，2344 KiB | 18 ms，2468 KiB |
| runc 1.4.2 | 23 ms，2332 KiB | 21 ms，2296 KiB | 18 ms，2324 KiB |


## 安装

`v` 标签对应的 GitHub Release 安装包由 GitHub Actions 构建，不是在开发者机器上构建的。

`dev/package.sh` 把启动器和原生镜像打成 `coaming-<version>-linux-amd64.tar.gz`。版本号来自 `Coaming.VERSION`。`coaming` 是小启动器，`coaming-runtime` 是原生镜像，两个文件放在同一目录。

containerd 把 `BinaryName` 指到解压出的 `coaming`，然后重启那个 containerd。shim 仍是 `containerd-shim-runc-v2`。cri-o 把 `runtime_path` 指到同一个二进制，然后重启 crio。

要退回时，把 `BinaryName` 或 `runtime_path` 指回原来的运行时，并且只重启那个服务。


## 许可证

[Apache-2.0](LICENSE)
