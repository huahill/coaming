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

cri-tools `critest` v1.37.0 against containerd, on the current binary:

```
Ran 119 of 142 Specs in 704.478 seconds
FAIL! -- 96 Passed | 23 Failed | 0 Pending | 23 Skipped
```

`kill` no longer fails with errno 13. Exec with a tty returns. A read-only bind leaves a nested mount writable, and exec applies uid, gid, and supplemental groups.

The 23 failures from that full run:

- port forward, host-network port forward, and both host-port mappings
- attach cleanup, log reopen, and the multi-container log
- execSync timeout
- pod sandbox metrics and multi-container pod network
- HostPID, PodPID, and RunAsUserName
- ReadOnlyRootfs, privileged disabled, and dropping capabilities
- three seccomp profiles
- deprecated permissive AppArmor profile
- pod user namespace when an idmap mount is required
- OOM reason stays `Error` instead of `OOMKilled`

A later focused rerun, not a new full suite, passed the runtime-default and localhost seccomp profiles, HostPID, PodPID, execSync timeout, and pod sandbox metrics. Host-port mapping had already passed. These were still failing when the test machine stopped responding, so the full suite was not rerun:

- port forward and host-network port forward. The host-network case can reach `127.0.0.1:12003` directly, then the sandbox is stopped and the forward dial gets connection refused.
- port mapping with only a container port. The sandbox is stopped about a second after it starts, and the HTTP check then times out.
- reopening the container log. The new log file stays empty.
- a pod user namespace when an idmap mount is required. The sandbox exits with status 1 before the container starts.
- OOM. A memory limit still does not set `memory.swap.max`, so the case was not rerun. The cgroup write is in the source and is not in the installed native image yet.

cri-o `critest` v1.37.0:

```
Ran 120 of 142 Specs in 467.912 seconds
FAIL! -- 49 Passed | 71 Failed | 0 Pending | 22 Skipped
```

Most cri-o failures are `CreateContainer failed as the sandbox was stopped`. A few creates return `coaming: Invalid argument`.


## runtime-tools

Passed inside the boundary, with the systemd wrapper: `delete`, `killsig`, `config_updates_without_affect`, `linux_ns_itype`, `linux_ns_path_type`, `misc_props`, `process_rlimits_fail`, `linux_rootfs_propagation`, `process_capabilities`, `hooks_stdin`, `mounts`.

Outside the boundary, and rejected rather than emulated:

- `delete_resources` and `delete_only_create_resources` look for a cgroup v1 pids hierarchy.
- `linux_cgroups_*` and `linux_cgroups_relative_*` require cgroup v1. They were not re-run. A v1 request is rejected.
