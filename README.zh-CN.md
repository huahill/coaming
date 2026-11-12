[English](README.md) · **中文**

# coaming

GraalVM Native Image OCI 容器运行时，使用 Java + Panama FFM 和 Rust 编写。实现 [OCI Runtime Specification](https://github.com/opencontainers/runtime-spec)。

## 构建

`mvn test` 需要 JDK 25 和 Maven 4。POM 的 `modelVersion` 是 4.1.0。

`mvn -Pnative -DskipTests package` 还需要带 `native-image` 的 GraalVM CE for JDK 25，以及能编译 edition 2024 的 Rust。Linux 上要链接 libseccomp，所以还需要 C 编译器和 libseccomp。这个 profile 会先构建 `native/nsexec`，再构建原生镜像。

```sh
mvn test
mvn -Pnative -DskipTests package
```

`target/coaming` 是原生镜像。启动器是 `native/nsexec/target/release/coaming`。`dev/package.sh` 把这两个文件打进同一个压缩包。GitHub Release 里的安装包由 Actions 根据 `v` 标签构建。

## 运行

bundle 里放 `config.json` 和它指定的 rootfs。`--root` 是状态目录，默认 `/run/coaming`。`--log` 写日志，`--log-format` 取 `text` 或 `json`。调用方使用 systemd cgroup 路径时加上 `--systemd-cgroup`。

```sh
coaming --version
coaming features
coaming --root /run/coaming create --bundle /path/to/bundle demo
coaming --root /run/coaming start demo
coaming --root /run/coaming state demo
coaming --root /run/coaming kill demo TERM
coaming --root /run/coaming delete demo
```

`coaming --version` 退出码为 0。唯一一行是 `coaming version 0.1.0`。`state` 输出的 JSON 包含 `id`、`status`、`pid` 和 `bundle`。其余命令是 `exec`、`update`、`events` 和 `ps`。

## 边界

`coaming features` 报告的 OCI 范围是 1.0.0 到 1.2.0。Linux 命名空间是 `ipc`、`mount`、`network`、`pid`、`user` 和 `uts`。挂载 idmap 已启用。cgroup v2 和 systemd cgroup 已启用。cgroup v1 和 systemd 用户会话不支持。非空的 `process.selinuxLabel` 会被拒绝。

containerd 把 `BinaryName` 指到这个二进制，shim 仍是 `containerd-shim-runc-v2`。cri-o 把 `runtime_path` 指到这个二进制。

一致性测试记录在 [dev/conformance/README.md](dev/conformance/README.md)。containerd 上 cri-tools `critest` v1.37.0 在 142 项里跑了 119 项：119 通过，0 失败，23 项跳过。cri-o 1.37.3 在 142 项里跑了 118 项：116 通过，2 项失败。那 2 项是指标配置错误，单独重跑后通过。修改之后没有重跑整套 cri-o。runtime-tools v0.9.0 通过了这条边界内的用例。要求 cgroup v1 的用例会被拒绝。

## 对比

一台 cgroup v2 机器、一份 bundle、三次采样。机器、内核和发行版没有记录。计时从 `create` 开始，到 `start` 返回结束，此时 `/bin/sleep` 已经进入。这条路径不经过 containerd 或 cri-o，也不测量镜像拉取、容器密度或安静的实验室环境。没有中位数，也没有分位数。同一套做法更早的几次运行得到过不同的数字。

RSS 是 `/bin/sleep` 的 `VmRSS`，不是 `coaming` 或 `runc` 的内存。它不能说明哪个运行时更省内存。

下面几行只是那次运行的记录，不是结论，也不能当作任一运行时更快或更小的证据。

| 运行时 | 第 1 次 | 第 2 次 | 第 3 次 |
| --- | --- | --- | --- |
| coaming 0.1.0 | 23 ms，2348 KiB | 32 ms，2344 KiB | 18 ms，2468 KiB |
| runc 1.4.2 | 23 ms，2332 KiB | 21 ms，2296 KiB | 18 ms，2324 KiB |


## 安装

`v` 标签对应的 GitHub Release 安装包由 GitHub Actions 构建，不是在开发者机器上构建的。

`dev/package.sh` 把启动器和原生镜像打成 `coaming-<version>-linux-amd64.tar.gz`。版本号来自 `Coaming.VERSION`。`coaming` 是小启动器，`coaming-runtime` 是原生镜像，两个文件放在同一目录。把两个文件解压到同一目录，例如 `/usr/local/bin`。下面的路径使用这个位置。

下面各节把一个服务指到这个 `coaming`。不要替换 `/usr/bin/runc`。containerd 的 shim 仍是 `containerd-shim-runc-v2`。`SystemdCgroup` 必须和节点上现有 `runc` 运行时一致。

要退回时，把该服务指回原来的运行时，并且只重启那个服务。


## Docker

`/etc/docker/daemon.json`：

```json
{
  "runtimes": {
    "coaming": {
      "path": "/usr/local/bin/coaming"
    }
  }
}
```

```sh
sudo systemctl reload docker
docker run --rm --runtime coaming hello-world
```

`"default-runtime": "coaming"` 会把它设为 daemon 的默认运行时。已经创建的容器仍使用创建时的运行时。


## Kubernetes

containerd 2 的 `/etc/containerd/config.toml`：

```toml
version = 3

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming]
  runtime_type = "io.containerd.runc.v2"
  [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming.options]
    BinaryName = "/usr/local/bin/coaming"
    Root = "/run/coaming"
    SystemdCgroup = true
```

containerd 1 使用 `version = 2`，插件名用 `io.containerd.grpc.v1.cri` 替换 `io.containerd.cri.v1.runtime`。把 `default_runtime_name` 设为 `"coaming"` 后，新 Pod 都使用 coaming。

```sh
sudo systemctl restart containerd
```

```yaml
apiVersion: node.k8s.io/v1
kind: RuntimeClass
metadata:
  name: coaming
handler: coaming
```

Pod 用 `spec.runtimeClassName: coaming` 选择它。nerdctl 使用同一份 containerd 配置。

CRI-O 读取 `/etc/crio/crio.conf.d/coaming.conf`：

```toml
[crio.runtime.runtimes.coaming]
runtime_path = "/usr/local/bin/coaming"
runtime_type = "oci"
runtime_root = "/run/coaming"
```

```sh
sudo systemctl restart crio
```

上面的 RuntimeClass 使用同一个 handler 名称。在 `[crio.runtime]` 中设置 `default_runtime = "coaming"` 即作为默认运行时。


## k3s

k3s 使用自己的 containerd，Docker 的配置对它无效。2025 年 2 月之后的版本自带 containerd 2。创建 `/var/lib/rancher/k3s/agent/etc/containerd/config-v3.toml.tmpl`：

```toml
{{ template "base" . }}

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming]
  runtime_type = "io.containerd.runc.v2"
  [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming.options]
    BinaryName = "/usr/local/bin/coaming"
    Root = "/run/coaming"
    SystemdCgroup = true
```

containerd 1.7 使用 `config.toml.tmpl` 和 `io.containerd.grpc.v1.cri`。重启 `k3s` 或 `k3s-agent`。k3s 不会自行发现 coaming。用上面的 RuntimeClass 选择，或者以 `--default-runtime coaming` 启动 k3s。


## k0s

k0s 1.36 及以后同样自带 containerd 2。在 `/etc/k0s/containerd.toml` 仍由 k0s 管理时，添加 `/etc/k0s/containerd.d/coaming.toml`：

```toml
version = 3

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming]
  runtime_type = "io.containerd.runc.v2"
  [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming.options]
    BinaryName = "/usr/local/bin/coaming"
    Root = "/run/coaming"
    SystemdCgroup = true
```

该目录变化后，k0s 会重新加载 containerd。更早的 k0s 使用 containerd 1，配置为 `version = 2` 和 `io.containerd.grpc.v1.cri`。


## Podman

```sh
podman --runtime /usr/local/bin/coaming run --rm alpine echo ok
```

`/etc/containers/containers.conf`：

```toml
[engine.runtimes]
coaming = ["/usr/local/bin/coaming"]
```

之后使用 `podman --runtime coaming`。


## 许可证

[Apache-2.0](LICENSE)
