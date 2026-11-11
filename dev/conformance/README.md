# Conformance

Pass means every case that stays inside the supported boundary: Linux, cgroup v2, no SELinux, no cgroup v1. A case outside that boundary must fail closed, not report success.

## Tools

Versions are pinned in `versions.txt`.

```sh
# runtime-tools v0.9.0, from its GOPATH checkout
sudo RUNTIME=/path/to/coaming make localvalidation
```

`make localvalidation` does not pass `--systemd-cgroup`. On this runtime a device allowlist is rejected unless that flag is set, so the suite has to be pointed at a wrapper that execs `coaming --systemd-cgroup "$@"`. That flag is required; skipping the device rules would be the wrong result.

```sh
critest --runtime-endpoint unix:///run/coaming-containerd/containerd.sock
critest --runtime-endpoint unix:///var/run/crio/crio.sock
```

cri-tools `critest` v1.37.0 is the containerd CRI run and the cri-o run. It has not been executed yet.

## runtime-tools

Ran on the cgroup v2 systemd host with the wrapper above, against the binary before the later exec-wait fix:

- 28 cases passed, including create, hooks, hostname, seccomp, masked and read-only paths, id mappings, and prestart/poststart/poststop.
- cgroup v1 cases failed closed: `linux_cgroups_blkio`, `linux_cgroups_cpus`, `linux_cgroups_devices`, `linux_cgroups_hugetlb`, `linux_cgroups_memory`, `linux_cgroups_network`, `linux_cgroups_pids`, and the `linux_cgroups_relative_*` twins. v1 is not implemented.
- `linux_mount_label` passed. A non-empty SELinux label is still rejected at create; this case did not require applying one.

Still failing inside the boundary after that run, and not all of them have been re-run:

- `killsig` still fails. `USR1` and `USR2` are accepted, and `hooks_stdin` now passes, but the trap in `killsig` does not create its file.
- `delete`, `delete_only_create_resources`, `delete_resources` do not yet match the lifecycle rules.
- `linux_ns_itype`, `linux_ns_path_type`, `linux_rootfs_propagation`, `misc_props`, `mounts`, `process_capabilities`, `process_rlimits_fail`, `config_updates_without_affect`.

`process_capabilities` was missing the default `/dev` nodes. Those nodes are created when `linux.devices` is absent. That case has not been re-run.
