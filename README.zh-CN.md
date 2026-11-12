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

`target/coaming` 是原生镜像。Rust 链进这个文件，只在进程还是单线程时运行。`dev/package.sh` 把这一个文件打进压缩包。GitHub Release 里的安装包由 Actions 根据 `v` 标签构建。

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

一份 busybox bundle，进程是 `sleep 30`。计时命令是 `create`、`start` 和 `delete -f`。`delete` 会停止该进程。hyperfine 预热 10 次，正式测量 100 次。每次正式测量前执行 `sync` 并清空页缓存，这一步不计入时间。直接调用运行时二进制。

配置是 OCI 1.2.0，ambient capabilities 为空，没有设备 cgroup 规则。

Ubuntu 26.10，内核 7.3.0-10-generic，12 核，32 GiB，cgroup v2。

| 运行时 | 时间（平均 ± σ） | 范围（最小 … 最大） | 相对 coaming | 版本 |
| --- | --- | --- | --- | --- |
| crun | 7.5 ms ± 2.5 ms | 5.8 ms … 22.5 ms | 12% | 1.30.1 |
| youki | 36.1 ms ± 6.2 ms | 25.8 ms … 50.3 ms | 56% | 0.7.0 |
| coaming | 64.4 ms ± 6.7 ms | 55.2 ms … 91.2 ms | 100% | 0.1.0 |
| runc | 131.7 ms ± 3.9 ms | 125.5 ms … 142.5 ms | 204% | 1.1.14 |

测试细节

`hyperfine` 2.0.0，参数是 `--shell=default --warmup 10 --min-runs 100`。bundle 是 `/var/tmp/coaming-bench/tutorial`。每次正式测量前先删掉名为 `a` 的残留容器，然后：

```sh
sync
echo 3 > /proc/sys/vm/drop_caches
```

```sh
hyperfine --shell=default --warmup 10 --min-runs 100 \
  --prepare '/tmp/coaming/target/coaming --root /run/coaming-bench delete --force a >/dev/null 2>&1 || true; sync; echo 3 > /proc/sys/vm/drop_caches' \
  '/tmp/coaming/target/coaming --root /run/coaming-bench create --bundle /var/tmp/coaming-bench/tutorial a && /tmp/coaming/target/coaming --root /run/coaming-bench start a && /tmp/coaming/target/coaming --root /run/coaming-bench delete --force a'

hyperfine --shell=default --warmup 10 --min-runs 100 \
  --prepare 'runc --root /run/runc-bench delete -f a >/dev/null 2>&1 || true; sync; echo 3 > /proc/sys/vm/drop_caches' \
  'runc --root /run/runc-bench create -b /var/tmp/coaming-bench/tutorial a && runc --root /run/runc-bench start a && runc --root /run/runc-bench delete -f a'

hyperfine --shell=default --warmup 10 --min-runs 100 \
  --prepare '/var/tmp/coaming-bench/bin/crun --root /run/crun-bench delete -f a >/dev/null 2>&1 || true; sync; echo 3 > /proc/sys/vm/drop_caches' \
  '/var/tmp/coaming-bench/bin/crun --root /run/crun-bench create -b /var/tmp/coaming-bench/tutorial a && /var/tmp/coaming-bench/bin/crun --root /run/crun-bench start a && /var/tmp/coaming-bench/bin/crun --root /run/crun-bench delete -f a'

hyperfine --shell=default --warmup 10 --min-runs 100 \
  --prepare '/var/tmp/coaming-bench/bin/youki -r /run/youki-bench delete -f a >/dev/null 2>&1 || true; sync; echo 3 > /proc/sys/vm/drop_caches' \
  '/var/tmp/coaming-bench/bin/youki -r /run/youki-bench create -b /var/tmp/coaming-bench/tutorial a && /var/tmp/coaming-bench/bin/youki -r /run/youki-bench start a && /var/tmp/coaming-bench/bin/youki -r /run/youki-bench delete -f a'
```

```text
coaming version 0.1.0
runc version 1.1.14
crun version 1.30.1
youki version: 0.7.0
Kernel-Release    7.3.0-10-generic
Kernel-Version    #10-Ubuntu SMP PREEMPT_DYNAMIC Thu Oct 22 07:35:54 UTC 2026
Architecture      x86_64
Operating System  Ubuntu 26.10
Cores             12
Total Memory      31357
Cgroup setup      unified
```


## 安装

`v` 标签对应的 GitHub Release 安装包由 GitHub Actions 构建，不是在开发者机器上构建的。

`dev/package.sh` 按当前机器命名压缩包：`coaming-<version>-linux-amd64.tar.gz` 或 `coaming-<version>-linux-arm64.tar.gz`。版本号来自 `Coaming.VERSION`。压缩包里只有一个文件 `coaming`，就是原生镜像。把它解压为 `/usr/local/bin/coaming`。下面的路径使用这个位置。

`v` 标签的 GitHub Release 包含这两个压缩包，由 Actions 构建。上面的一致性测试是在 amd64 上测的。arm64 压缩包在 `ubuntu-24.04-arm` 上构建，没有跑过那套测试。

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
