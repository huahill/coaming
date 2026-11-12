**English** · [中文](README.zh-CN.md)

# coaming

A GraalVM Native Image OCI container runtime, written in Java + Panama FFM and Rust. Implements the [OCI Runtime Specification](https://github.com/opencontainers/runtime-spec).

## Build

`mvn test` needs JDK 25 and Maven 4. The POM uses `modelVersion` 4.1.0.

`mvn -Pnative -DskipTests package` also needs GraalVM CE for JDK 25, including `native-image`, and a Rust toolchain that can build edition 2024. On Linux it links libseccomp, so a C compiler and libseccomp are required. The profile builds `native/nsexec` and then the native image.

```sh
mvn test
mvn -Pnative -DskipTests package
```

`target/coaming` is the native image. The launcher is `native/nsexec/target/release/coaming`. `dev/package.sh` puts those two files in one archive. The GitHub Release asset is produced by Actions from a `v` tag.

## Run

A bundle contains `config.json` and the rootfs it names. `--root` is the state directory and defaults to `/run/coaming`. `--log` writes a log, and `--log-format` is `text` or `json`. Pass `--systemd-cgroup` when the caller uses systemd cgroup paths.

```sh
coaming --version
coaming features
coaming --root /run/coaming create --bundle /path/to/bundle demo
coaming --root /run/coaming start demo
coaming --root /run/coaming state demo
coaming --root /run/coaming kill demo TERM
coaming --root /run/coaming delete demo
```

`coaming --version` exits 0. Its one line is `coaming version 0.1.0`. `state` prints JSON with `id`, `status`, `pid`, and `bundle`. The other commands are `exec`, `update`, `events`, and `ps`.

## Boundary

`coaming features` reports OCI 1.0.0 through 1.2.0. Linux namespaces are `ipc`, `mount`, `network`, `pid`, `user`, and `uts`. Mount idmap is enabled. cgroup v2 and systemd cgroup are enabled. cgroup v1 and systemd user sessions are not. A non-empty `process.selinuxLabel` is rejected.

containerd sets `BinaryName` to this binary and keeps the shim `containerd-shim-runc-v2`. cri-o sets `runtime_path` to this binary.

Conformance is recorded in [dev/conformance/README.md](dev/conformance/README.md). On containerd, cri-tools `critest` v1.37.0 ran 119 of 142 specs: 119 passed, 0 failed, 23 skipped. On cri-o 1.37.3 it ran 118 of 142: 116 passed and 2 failed. Those two were a metrics configuration error and passed when rerun alone. The full cri-o suite was not rerun after that change. runtime-tools v0.9.0 passed the cases inside this boundary. Cases that require cgroup v1 are rejected.

## Comparison

One cgroup v2 machine, one bundle, three samples. The machine, kernel, and distribution were not recorded. The clock starts at `create` and stops when `start` returns, after `/bin/sleep` has been entered. This does not go through containerd or cri-o, and it does not measure image pull, density, or a quiet lab run. There is no median or percentile. Earlier runs on the same setup produced different numbers.

RSS is `VmRSS` of `/bin/sleep`, not of `coaming` or `runc`. It does not show which runtime uses less memory.

These rows are a record of that run. They are not a result, and they are not evidence that either runtime is faster or smaller.

| runtime | sample 1 | sample 2 | sample 3 |
| --- | --- | --- | --- |
| coaming 0.1.0 | 23 ms, 2348 KiB | 32 ms, 2344 KiB | 18 ms, 2468 KiB |
| runc 1.4.2 | 23 ms, 2332 KiB | 21 ms, 2296 KiB | 18 ms, 2324 KiB |


## Install

The GitHub Release asset for a `v` tag is built by GitHub Actions, not on a developer machine.

`dev/package.sh` packs the launcher and the native image into `coaming-<version>-linux-amd64.tar.gz`. The version is `Coaming.VERSION`. `coaming` is the small launcher. `coaming-runtime` is the native image, and the two files stay in the same directory. Unpack both into one directory, such as `/usr/local/bin`. The paths below use that location.

The sections below point one service at that `coaming`. Leave `/usr/bin/runc` in place. containerd keeps the shim `containerd-shim-runc-v2`. `SystemdCgroup` must match the `runc` runtime already used on the node.

To roll back, point that service back at the previous runtime and restart only that service.


## Docker

`/etc/docker/daemon.json`:

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

`"default-runtime": "coaming"` makes it the daemon default. Containers already created stay on the runtime that created them.


## Kubernetes

containerd 2, in `/etc/containerd/config.toml`:

```toml
version = 3

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming]
  runtime_type = "io.containerd.runc.v2"
  [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming.options]
    BinaryName = "/usr/local/bin/coaming"
    Root = "/run/coaming"
    SystemdCgroup = true
```

containerd 1 uses `version = 2` and `io.containerd.grpc.v1.cri` instead of `io.containerd.cri.v1.runtime`. Set `default_runtime_name = "coaming"` to use coaming for every new pod.

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

A pod selects it with `spec.runtimeClassName: coaming`. nerdctl uses this same containerd runtime.

CRI-O reads `/etc/crio/crio.conf.d/coaming.conf`:

```toml
[crio.runtime.runtimes.coaming]
runtime_path = "/usr/local/bin/coaming"
runtime_type = "oci"
runtime_root = "/run/coaming"
```

```sh
sudo systemctl restart crio
```

The RuntimeClass above uses the same handler name. `default_runtime = "coaming"` under `[crio.runtime]` makes it the default.


## k3s

k3s runs its own containerd. Docker's configuration does not apply to it. Releases since February 2025 ship containerd 2. Create `/var/lib/rancher/k3s/agent/etc/containerd/config-v3.toml.tmpl`:

```toml
{{ template "base" . }}

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming]
  runtime_type = "io.containerd.runc.v2"
  [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming.options]
    BinaryName = "/usr/local/bin/coaming"
    Root = "/run/coaming"
    SystemdCgroup = true
```

containerd 1.7 uses `config.toml.tmpl` and `io.containerd.grpc.v1.cri`. Restart `k3s` or `k3s-agent`. k3s does not discover coaming on its own. Select it with the RuntimeClass above, or start k3s with `--default-runtime coaming`.


## k0s

k0s 1.36 and later also ship containerd 2. While `/etc/k0s/containerd.toml` is still managed by k0s, add `/etc/k0s/containerd.d/coaming.toml`:

```toml
version = 3

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming]
  runtime_type = "io.containerd.runc.v2"
  [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.coaming.options]
    BinaryName = "/usr/local/bin/coaming"
    Root = "/run/coaming"
    SystemdCgroup = true
```

k0s reloads containerd when that directory changes. Older k0s uses containerd 1: `version = 2` and `io.containerd.grpc.v1.cri`.


## Podman

```sh
podman --runtime /usr/local/bin/coaming run --rm alpine echo ok
```

`/etc/containers/containers.conf`:

```toml
[engine.runtimes]
coaming = ["/usr/local/bin/coaming"]
```

`podman --runtime coaming` uses that name.


## License

[Apache-2.0](LICENSE)
