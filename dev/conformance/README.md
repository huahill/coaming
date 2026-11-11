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

cri-tools `critest` v1.37.0 has not been run.

## runtime-tools

Re-ran the cases that had failed inside the boundary, with the wrapper, after the lifecycle fixes:

Passed: `linux_ns_path_type`, `misc_props`, `process_rlimits_fail`, `linux_rootfs_propagation`, `process_capabilities`, `hooks_stdin`, `mounts`. The created-container half of `delete` also passes.

Still failing:

- `delete` of a container whose config is `sleep 30`. Delete returns success and removes the container. The created-container case is refused.
- `killsig`. `TERM`, `USR1`, and `USR2` are accepted, but the trap does not create the expected file in the rootfs.
- `config_updates_without_affect`. `start` succeeds after `config.json` is replaced. The process inside the container then exits 1.
- `linux_ns_itype`. A config with no `linux.namespaces` gets the default pid, mount, uts, ipc, and network namespaces. The spec allows that default. This test wants the host namespaces instead.
- `delete_resources` looks for a cgroup v1 pids hierarchy. v1 is not implemented.
- `delete_only_create_resources` still fails; its output has no assertion text.

cgroup v1 cases (`linux_cgroups_*` and `linux_cgroups_relative_*`) were not re-run. They fail closed.
