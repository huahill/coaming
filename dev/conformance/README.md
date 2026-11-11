# Conformance

A case passes only when it stays inside the supported boundary: Linux, cgroup v2, no SELinux, and no cgroup v1. A case outside that boundary must fail closed.

## Tools

Versions are pinned in `versions.txt`.

```sh
# runtime-tools v0.9.0, built from the GOPATH checkout
sudo RUNTIME=/path/to/coaming make localvalidation
```

`make localvalidation` cannot pass `--systemd-cgroup`. Device allowlists are rejected without that flag, so the run uses a wrapper that execs `coaming --systemd-cgroup "$@"`. The wrapper is not part of this repository.

```sh
critest --runtime-endpoint unix:///run/coaming-containerd/containerd.sock
critest --runtime-endpoint unix:///var/run/crio/crio.sock
```

cri-tools `critest` v1.37.0 was run against containerd. It did not finish.

`kill` initially returned errno 13. containerd's default AppArmor profile accepts signals from peer `unconfined`, `runc`, or `crun`. The named `coaming` profile stays a distinct peer even with `flags=(unconfined)`. The launcher now drops that label before signalling. After that change, container stop no longer failed with errno 13.

The same run then stopped in `runtime should support exec with tty=true and stdin=true`. The non-tty exec in that run returned. The launcher creates a pty and sends the master back on `--console-socket`, but that spec still did not return.

Before the hang, these in-boundary specs failed:

- OOM: the status reason stayed `Error` instead of `OOMKilled`.
- host-network port forward: the connection to the container port was refused.
- `execSync` with a timeout: containerd reported `cannot exec in a stopped state`.

`gcr.io` pulls from containerd still time out when the daemon dials the registry directly. Images already present are reused. cri-o `critest` has not been run.

## runtime-tools

Passed inside the boundary, with the systemd wrapper: `delete`, `killsig`, `config_updates_without_affect`, `linux_ns_itype`, `linux_ns_path_type`, `misc_props`, `process_rlimits_fail`, `linux_rootfs_propagation`, `process_capabilities`, `hooks_stdin`, `mounts`.

Outside the boundary, and rejected rather than emulated:

- `delete_resources` and `delete_only_create_resources` look for a cgroup v1 pids hierarchy.
- `linux_cgroups_*` and `linux_cgroups_relative_*` require cgroup v1. They were not re-run. A v1 request is rejected.
