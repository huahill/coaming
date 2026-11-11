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

cri-tools `critest` v1.37.0. containerd is `coaming-containerd` at `unix:///run/coaming-containerd/containerd.sock`. cri-o is 1.37.3 at `unix:///var/run/crio/crio.sock`. k3s was stopped for both runs. Docker's containerd and dockerd were not restarted.

containerd:

```
Ran 119 of 142 Specs in 237.641 seconds
SUCCESS! -- 119 Passed | 0 Failed | 0 Pending | 23 Skipped
```

cri-o:

```
Ran 118 of 142 Specs in 206.723 seconds
FAIL! -- 116 Passed | 2 Failed | 0 Pending | 24 Skipped
```

The two cri-o failures are `ListMetricDescriptors` and `ListPodSandboxMetrics`. cri-o returns only `container_last_seen`. The same binary against containerd returns the cAdvisor set. Setting `included_pod_metrics = ["all"]` does not add those descriptors. That list is cri-o's CRI metrics catalog, not a cgroup file coaming can supply.

cri-o's nft hostport table DNATs `127.0.0.1` to the pod and does not masquerade that source. Localhost host ports then time out. The test machine keeps `POSTROUTING -s 127.0.0.0/8 -d 10.86.0.0/16 -j MASQUERADE` in `coaming-crio-masquerade.service`. With that rule, cri-o host-port mapping passes.


## runtime-tools

Passed inside the boundary, with the systemd wrapper: `delete`, `killsig`, `config_updates_without_affect`, `linux_ns_itype`, `linux_ns_path_type`, `misc_props`, `process_rlimits_fail`, `linux_rootfs_propagation`, `process_capabilities`, `hooks_stdin`, `mounts`.

Outside the boundary, and rejected rather than emulated:

- `delete_resources` and `delete_only_create_resources` look for a cgroup v1 pids hierarchy.
- `linux_cgroups_*` and `linux_cgroups_relative_*` require cgroup v1. They were not re-run. A v1 request is rejected.
