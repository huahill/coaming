mod bind_mounts;

/// Probe whether this process can enter a user namespace before any extra thread exists.
///
/// `0` means this process had one thread and `unshare(CLONE_NEWUSER)` succeeded.
/// `1` means the host is not Linux.
/// `2` means the thread count was not one; `unshare` is not called.
/// `3` means `unshare` failed, or the thread count could not be read.
#[unsafe(no_mangle)]
pub extern "C" fn coaming_nsexec_probe() -> i32 {
    #[cfg(not(target_os = "linux"))]
    {
        1
    }
    #[cfg(target_os = "linux")]
    {
        let threads = match thread_count() {
            Ok(count) => count,
            Err(_) => return 3,
        };
        if threads != 1 {
            return 2;
        }
        let rc = unsafe { libc::unshare(libc::CLONE_NEWUSER) };
        if rc == 0 { 0 } else { 3 }
    }
}

#[cfg(target_os = "linux")]
fn thread_count() -> std::io::Result<usize> {
    let mut count = 0usize;
    for entry in std::fs::read_dir("/proc/self/task")? {
        entry?;
        count += 1;
    }
    Ok(count)
}

#[cfg(target_os = "linux")]
#[used]
#[unsafe(link_section = ".init_array.00001")]
static COAMING_EARLY_INIT: unsafe extern "C" fn() = coaming_nsexec_init;

#[cfg(target_os = "linux")]
unsafe extern "C" fn coaming_nsexec_init() {
    match first_argument().as_deref() {
        Some("probe") => run_probe(),
        Some("init") => enter_container(),
        Some("exec-init") => enter_exec(),
        _ => {}
    }
}

#[cfg(target_os = "linux")]
fn first_argument() -> Option<String> {
    let data = std::fs::read("/proc/self/cmdline").unwrap_or_default();
    let mut parts = data.split(|byte| *byte == 0).filter(|part| !part.is_empty());
    let _argv0 = parts.next();
    parts.next().and_then(|part| String::from_utf8(part.to_vec()).ok())
}

#[cfg(target_os = "linux")]
fn run_probe() {
    if std::env::var_os("COAMING_PROBE_FAIL").is_some_and(|value| value == "1") {
        unsafe { libc::_exit(81) };
    }
    let threads = thread_count().unwrap_or(0);
    let rc = coaming_nsexec_probe();
    set_env("COAMING_PROBE_RC", &rc.to_string());
    set_env("COAMING_PROBE_THREADS", &threads.to_string());
}

#[cfg(target_os = "linux")]
fn enter_container() {
    if std::env::var_os("COAMING_INIT_STAGE").is_some_and(|value| value == "jvm") {
        return;
    }
    prepare_bind_fds();
    let Some(state) = argument_value("--state") else {
        unsafe { libc::_exit(74) };
    };
    let pid_path = std::ffi::CString::new(format!("{state}/pid")).expect("pid path");
    let args: Vec<std::ffi::CString> = std::fs::read("/proc/self/cmdline")
        .unwrap_or_default()
        .split(|byte| *byte == 0)
        .filter(|part| !part.is_empty())
        .map(|part| std::ffi::CString::new(part.to_vec()).expect("argument"))
        .collect();
    if args.is_empty() {
        unsafe { libc::_exit(75) };
    }
    let parts = cmdline_parts();
    let (mut unshare, mut joins) = namespace_actions(&parts);
    let uid_map = argument_value("--uid-map").unwrap_or_default();
    let gid_map = argument_value("--gid-map").unwrap_or_default();
    prepare_user_namespace(&mut unshare, &mut joins, &uid_map, &gid_map);
    set_env("COAMING_INIT_STAGE", "jvm");
    let mut flags: libc::c_int = 0;
    let mut new_pid = false;
    for kind in &unshare {
        if kind == "none" {
            continue;
        }
        if kind == "pid" {
            new_pid = true;
            continue;
        }
        flags |= clone_flag(kind);
    }
    let mut opened = Vec::new();
    for (kind, path) in &joins {
        let c_path = match std::ffi::CString::new(path.clone()) {
            Ok(value) => value,
            Err(_) => unsafe { libc::_exit(89) },
        };
        let fd = unsafe { libc::open(c_path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
        if fd < 0 {
            unsafe { libc::_exit(88) };
        }
        opened.push((kind.clone(), fd));
    }
    unsafe {
        if flags != 0 && libc::unshare(flags) != 0 {
            libc::_exit(70);
        }
        let mut pid_join = -1;
        let mut mount_join = -1;
        for (kind, fd) in opened {
            if kind == "pid" {
                pid_join = fd;
                continue;
            }
            if kind == "mount" {
                mount_join = fd;
                continue;
            }
            if libc::setns(fd, clone_flag(&kind)) != 0 {
                libc::_exit(88);
            }
            libc::close(fd);
        }
        if new_pid && libc::unshare(libc::CLONE_NEWPID) != 0 {
            libc::_exit(70);
        }
        if pid_join >= 0 && libc::setns(pid_join, libc::CLONE_NEWPID) != 0 {
            libc::_exit(88);
        }
        if pid_join >= 0 {
            libc::close(pid_join);
        }
        let pid = libc::fork();
        if pid < 0 {
            libc::_exit(71);
        }
        if pid > 0 {
            write_pid(&pid_path, pid);
            libc::_exit(0);
        }
        if mount_join >= 0 && libc::setns(mount_join, libc::CLONE_NEWNS) != 0 {
            libc::_exit(88);
        }
        if mount_join >= 0 {
            libc::close(mount_join);
        }
        let mut argv: Vec<*const libc::c_char> = args.iter().map(|arg| arg.as_ptr()).collect();
        argv.push(std::ptr::null());
        libc::execve(args[0].as_ptr(), argv.as_ptr(), environ());
        libc::_exit(72);
    }
}


#[cfg(target_os = "linux")]
fn prepare_bind_fds() {
    let Some(bundle) = argument_value("--bundle") else {
        unsafe { libc::_exit(74) };
    };
    let path = format!("{bundle}/config.json");
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(error) => {
            complain(&format!("failed to read {path}: {error}"));
            unsafe { libc::_exit(92) };
        }
    };
    let mounts = bind_mounts::bind_mounts(&text);
    set_env("COAMING_BIND_COUNT", &mounts.len().to_string());
    for (index, mount) in mounts.iter().enumerate() {
        let opened = detach_bind(mount);
        let kept = park_fd(opened, 200 + index as i32);
        set_env(&format!("COAMING_BIND_FD_{index}"), &kept.to_string());
        set_env(&format!("COAMING_BIND_DST_{index}"), &mount.destination);
    }
}

#[cfg(target_os = "linux")]
fn detach_bind(mount: &bind_mounts::PreparedBind) -> i32 {
    let Ok(source) = std::ffi::CString::new(mount.source.clone()) else {
        complain("bind mount source contains NUL");
        unsafe { libc::_exit(92) };
    };
    // OPEN_TREE_CLONE is 1. A detached copy can be attached after setuid.
    let mut flags = 1 | libc::O_CLOEXEC;
    if mount.recursive {
        flags |= libc::AT_RECURSIVE;
    }
    let mounted = unsafe { libc::syscall(libc::SYS_open_tree, libc::AT_FDCWD, source.as_ptr(), flags) } as i32;
    if mounted < 0 {
        complain(&format!(
            "failed to open bind mount {}: {}",
            mount.source,
            std::io::Error::last_os_error()
        ));
        unsafe { libc::_exit(92) };
    }
    // mount_setattr rejects MS_REC in the propagation field. Recursion is AT_RECURSIVE.
    let mut setattr_flags = libc::AT_EMPTY_PATH;
    if mount.recursive {
        setattr_flags |= libc::AT_RECURSIVE;
    }
    let attr = MountAttr {
        attr_set: mount.attr_set,
        attr_clr: 0,
        propagation: 0,
        userns_fd: 0,
    };
    let empty = std::ffi::CString::new("").unwrap();
    let rc = unsafe {
        libc::syscall(
            libc::SYS_mount_setattr,
            mounted,
            empty.as_ptr(),
            setattr_flags,
            &attr as *const MountAttr,
            std::mem::size_of::<MountAttr>(),
        )
    };
    if rc != 0 {
        complain(&format!(
            "failed to prepare bind mount {}: {}",
            mount.source,
            std::io::Error::last_os_error()
        ));
        unsafe {
            libc::close(mounted);
            libc::_exit(92);
        }
    }
    mounted
}

#[cfg(target_os = "linux")]
fn park_fd(fd: i32, preferred: i32) -> i32 {
    unsafe {
        let mut target = preferred;
        loop {
            if libc::fcntl(target, libc::F_GETFD) == -1 {
                break;
            }
            target += 1;
            if target > 1023 {
                complain("no free descriptor for a bind mount");
                libc::_exit(92);
            }
        }
        if libc::dup2(fd, target) < 0 {
            complain("failed to preserve a bind mount descriptor");
            libc::_exit(92);
        }
        if fd != target {
            libc::close(fd);
        }
        target
    }
}

#[cfg(target_os = "linux")]
fn prepare_user_namespace(unshare: &mut Vec<String>, joins: &mut Vec<(String, String)>, uid_map: &str, gid_map: &str) {
    let create = unshare.iter().any(|kind| kind == "user");
    let join = joins.iter().find(|(kind, _)| kind == "user").map(|(_, path)| path.clone());
    unshare.retain(|kind| kind != "user");
    joins.retain(|(kind, _)| kind != "user");
    if create && join.is_some() {
        unsafe { libc::_exit(89) };
    }
    let requests = idmap_requests();
    if !requests.is_empty() && !create {
        complain("idmap requires a new user namespace");
        unsafe { libc::_exit(78) };
    }
    if let Some(path) = join {
        let c_path = std::ffi::CString::new(path).unwrap_or_else(|_| unsafe { libc::_exit(89) });
        unsafe {
            let fd = libc::open(c_path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC);
            if fd < 0 || libc::setns(fd, libc::CLONE_NEWUSER) != 0 {
                libc::_exit(88);
            }
            libc::close(fd);
            // Host uid 0 is outside the joined map, so it shows up as overflow.
            // Container uid 0 is mapped and can finish the remaining setup.
            if libc::setresgid(0, 0, 0) != 0 || libc::setresuid(0, 0, 0) != 0 {
                complain("failed to become container root after joining the user namespace");
                libc::_exit(77);
            }
        }
        return;
    }
    if !create {
        return;
    }
    if uid_map.is_empty() || gid_map.is_empty() {
        unsafe { libc::_exit(76) };
    }
    let mut ready = [0; 2];
    let mut cont = [0; 2];
    let mut idmap_socks = [-1; 2];
    unsafe {
        if !requests.is_empty()
            && libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0, idmap_socks.as_mut_ptr()) != 0
        {
            complain("idmap failed: socketpair");
            libc::_exit(78);
        }
        if libc::pipe(ready.as_mut_ptr()) != 0 || libc::pipe(cont.as_mut_ptr()) != 0 {
            libc::_exit(76);
        }
        let pid = libc::fork();
        if pid < 0 {
            libc::_exit(76);
        }
        if pid > 0 {
            libc::close(ready[1]);
            libc::close(cont[0]);
            if idmap_socks[1] >= 0 {
                libc::close(idmap_socks[1]);
            }
            read_byte(ready[0]);
            libc::close(ready[0]);
            if write_id_maps(pid, uid_map, gid_map).is_err() {
                libc::kill(pid, libc::SIGKILL);
                libc::_exit(if libc::geteuid() == 0 { 76 } else { 79 });
            }
            if !requests.is_empty() && !prepare_idmaps(pid, idmap_socks[0], &requests) {
                libc::kill(pid, libc::SIGKILL);
                libc::_exit(78);
            }
            if idmap_socks[0] >= 0 {
                libc::close(idmap_socks[0]);
            }
            write_byte(cont[1]);
            libc::close(cont[1]);
            let status = wait_status(pid);
            libc::_exit(status);
        }
        libc::close(ready[0]);
        libc::close(cont[1]);
        if idmap_socks[0] >= 0 {
            libc::close(idmap_socks[0]);
        }
        if libc::unshare(libc::CLONE_NEWUSER) != 0 {
            libc::_exit(76);
        }
        write_byte(ready[1]);
        libc::close(ready[1]);
        read_byte(cont[0]);
        libc::close(cont[0]);
        if !requests.is_empty() {
            receive_idmaps(idmap_socks[1], &requests);
            libc::close(idmap_socks[1]);
        }
        // The process is still host uid 0. That id is outside the new map, so
        // the namespace sees overflow uid 65534 and execve drops its capabilities.
        // Container uid 0 is the mapped host id and keeps full namespace caps.
        if libc::setresgid(0, 0, 0) != 0 || libc::setresuid(0, 0, 0) != 0 {
            libc::_exit(77);
        }
    }
}

#[cfg(target_os = "linux")]
struct IdmapRequest {
    source: String,
    dest: String,
    recursive_open: bool,
    recursive_attr: bool,
    attr_set: u64,
    propagation: u64,
}

#[cfg(target_os = "linux")]
#[repr(C)]
#[repr(C)]
struct MountAttr {
    attr_set: u64,
    attr_clr: u64,
    propagation: u64,
    userns_fd: u64,
}

#[cfg(target_os = "linux")]
fn idmap_requests() -> Vec<IdmapRequest> {
    let parts = cmdline_parts();
    let mut requests = Vec::new();
    let mut index = 0usize;
    while index < parts.len() {
        if parts[index] != "--idmap" {
            index += 1;
            continue;
        }
        if index + 6 >= parts.len() {
            unsafe { libc::_exit(89) };
        }
        let recursive_open = match parts[index + 3].as_str() {
            "1" => true,
            "0" => false,
            _ => unsafe { libc::_exit(89) },
        };
        let recursive_attr = match parts[index + 4].as_str() {
            "1" => true,
            "0" => false,
            _ => unsafe { libc::_exit(89) },
        };
        let Ok(attr_set) = parts[index + 5].parse::<u64>() else {
            unsafe { libc::_exit(89) };
        };
        let Ok(propagation) = parts[index + 6].parse::<u64>() else {
            unsafe { libc::_exit(89) };
        };
        requests.push(IdmapRequest {
            source: parts[index + 1].clone(),
            dest: parts[index + 2].clone(),
            recursive_open,
            recursive_attr,
            attr_set,
            propagation,
        });
        index += 7;
    }
    requests
}

#[cfg(target_os = "linux")]
fn prepare_idmaps(pid: i32, sock: i32, requests: &[IdmapRequest]) -> bool {
    let path = std::ffi::CString::new(format!("/proc/{pid}/ns/user")).expect("user namespace");
    let userns = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if userns < 0 {
        complain(&format!("idmap failed: open user namespace: {}", std::io::Error::last_os_error()));
        return false;
    }
    for request in requests {
        let mut flags = 1 | libc::O_CLOEXEC;
        if request.recursive_open {
            flags |= libc::AT_RECURSIVE;
        }
        let Ok(source) = std::ffi::CString::new(request.source.clone()) else {
            complain("idmap failed: source path contains NUL");
            unsafe { libc::close(userns) };
            return false;
        };
        let mounted = unsafe { libc::syscall(libc::SYS_open_tree, libc::AT_FDCWD, source.as_ptr(), flags) } as i32;
        if mounted < 0 {
            complain(&idmap_failure("open_tree", &request.source));
            unsafe { libc::close(userns) };
            return false;
        }
        let mut setattr_flags = libc::AT_EMPTY_PATH;
        // MS_REC is rejected unless mount_setattr itself is recursive.
        let mut propagation = request.propagation;
        if request.recursive_attr {
            setattr_flags |= libc::AT_RECURSIVE;
        } else {
            propagation &= !16384;
        }
        let attr = MountAttr {
            attr_set: request.attr_set | 0x0010_0000,
            attr_clr: 0,
            propagation,
            userns_fd: userns as u64,
        };
        let empty = std::ffi::CString::new("").unwrap();
        let rc = unsafe {
            libc::syscall(
                libc::SYS_mount_setattr,
                mounted,
                empty.as_ptr(),
                setattr_flags,
                &attr as *const MountAttr,
                std::mem::size_of::<MountAttr>(),
            )
        };
        if rc != 0 {
            complain(&idmap_failure("mount_setattr", &request.source));
            unsafe {
                libc::close(mounted);
                libc::close(userns);
            }
            return false;
        }
        if !send_fd(sock, mounted) {
            complain(&format!("idmap failed: pass mount: {}", std::io::Error::last_os_error()));
            unsafe {
                libc::close(mounted);
                libc::close(userns);
            }
            return false;
        }
        unsafe { libc::close(mounted) };
    }
    unsafe { libc::close(userns) };
    true
}

#[cfg(target_os = "linux")]
fn receive_idmaps(sock: i32, requests: &[IdmapRequest]) {
    set_env("COAMING_IDMAP_COUNT", &requests.len().to_string());
    for (index, request) in requests.iter().enumerate() {
        let Some(fd) = recv_fd(sock) else {
            complain("idmap failed: missing prepared mount");
            unsafe { libc::_exit(78) };
        };
        if unsafe { libc::fcntl(fd, libc::F_SETFD, 0) } != 0 {
            complain("idmap failed: keep prepared mount");
            unsafe { libc::_exit(78) };
        }
        set_env(&format!("COAMING_IDMAP_FD_{index}"), &fd.to_string());
        set_env(&format!("COAMING_IDMAP_DST_{index}"), &request.dest);
    }
}

#[cfg(target_os = "linux")]
fn send_fd(sock: i32, fd: i32) -> bool {
    unsafe {
        let mut byte = [1u8; 1];
        let mut iov = libc::iovec { iov_base: byte.as_mut_ptr().cast(), iov_len: 1 };
        let space = libc::CMSG_SPACE(std::mem::size_of::<i32>() as libc::c_uint) as usize;
        let mut buffer = vec![0u8; space];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = buffer.as_mut_ptr().cast();
        msg.msg_controllen = space as _;
        let hdr = libc::CMSG_FIRSTHDR(&msg);
        if hdr.is_null() {
            return false;
        }
        (*hdr).cmsg_level = libc::SOL_SOCKET;
        (*hdr).cmsg_type = libc::SCM_RIGHTS;
        (*hdr).cmsg_len = libc::CMSG_LEN(std::mem::size_of::<i32>() as libc::c_uint) as _;
        std::ptr::write_unaligned(libc::CMSG_DATA(hdr) as *mut i32, fd);
        libc::sendmsg(sock, &msg, 0) == 1
    }
}

#[cfg(target_os = "linux")]
fn recv_fd(sock: i32) -> Option<i32> {
    unsafe {
        let mut byte = [0u8; 1];
        let mut iov = libc::iovec { iov_base: byte.as_mut_ptr().cast(), iov_len: 1 };
        let space = libc::CMSG_SPACE(std::mem::size_of::<i32>() as libc::c_uint) as usize;
        let mut buffer = vec![0u8; space];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = buffer.as_mut_ptr().cast();
        msg.msg_controllen = space as _;
        if libc::recvmsg(sock, &mut msg, 0) < 1 {
            return None;
        }
        let hdr = libc::CMSG_FIRSTHDR(&msg);
        if hdr.is_null() || (*hdr).cmsg_level != libc::SOL_SOCKET || (*hdr).cmsg_type != libc::SCM_RIGHTS {
            return None;
        }
        Some(std::ptr::read_unaligned(libc::CMSG_DATA(hdr) as *const i32))
    }
}

#[cfg(target_os = "linux")]
fn idmap_failure(action: &str, path: &str) -> String {
    let err = std::io::Error::last_os_error();
    let prefix = match err.raw_os_error() {
        Some(libc::ENOSYS) | Some(libc::EOPNOTSUPP) | Some(libc::EINVAL) => "idmap is not supported",
        _ => "idmap failed",
    };
    format!("{prefix}: {action} {path}: {err}")
}

#[cfg(target_os = "linux")]
fn complain(message: &str) {
    let text = format!("coaming: {message}\n");
    unsafe {
        libc::write(2, text.as_ptr().cast(), text.len());
    }
}

#[cfg(target_os = "linux")]
fn write_id_maps(pid: i32, uid_map: &str, gid_map: &str) -> std::io::Result<()> {
    std::fs::write(format!("/proc/{pid}/setgroups"), "deny")?;
    if unsafe { libc::geteuid() } == 0 || (self_map(uid_map, unsafe { libc::geteuid() }) && self_map(gid_map, unsafe { libc::getegid() })) {
        std::fs::write(format!("/proc/{pid}/uid_map"), map_text(uid_map))?;
        return std::fs::write(format!("/proc/{pid}/gid_map"), map_text(gid_map));
    }
    if !run_map_helper("newuidmap", pid, uid_map) || !run_map_helper("newgidmap", pid, gid_map) {
        return Err(std::io::Error::other("rootless id mapping failed"));
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn self_map(spec: &str, id: u32) -> bool {
    let mut ranges = spec.split(',').filter(|item| !item.is_empty());
    let Some(only) = ranges.next() else { return false };
    if ranges.next().is_some() {
        return false;
    }
    let mut parts = only.split(':');
    let Some(_container) = parts.next() else { return false };
    let Some(host) = parts.next() else { return false };
    let Some(size) = parts.next() else { return false };
    parts.next().is_none() && host == id.to_string() && size == "1"
}

#[cfg(target_os = "linux")]
fn run_map_helper(program: &str, pid: i32, spec: &str) -> bool {
    let mut args = vec![program.to_string(), pid.to_string()];
    for item in spec.split(',').filter(|item| !item.is_empty()) {
        let mut parts = item.split(':');
        let Some(container) = parts.next() else { return false };
        let Some(host) = parts.next() else { return false };
        let Some(size) = parts.next() else { return false };
        if parts.next().is_some() {
            return false;
        }
        args.push(container.to_string());
        args.push(host.to_string());
        args.push(size.to_string());
    }
    let c_args: Vec<std::ffi::CString> = args
        .iter()
        .map(|arg| std::ffi::CString::new(arg.clone()).unwrap_or_else(|_| unsafe { libc::_exit(89) }))
        .collect();
    let mut argv: Vec<*const libc::c_char> = c_args.iter().map(|arg| arg.as_ptr()).collect();
    argv.push(std::ptr::null());
    unsafe {
        let child = libc::fork();
        if child < 0 {
            complain(&format!("rootless id mapping failed: cannot run {program}"));
            return false;
        }
        if child == 0 {
            libc::execvp(argv[0], argv.as_ptr());
            libc::_exit(127);
        }
        let status = wait_status(child);
        if status != 0 {
            complain(&format!("rootless id mapping failed: {program} exited {status}"));
            return false;
        }
    }
    true
}

#[cfg(target_os = "linux")]
fn map_text(spec: &str) -> String {
    let mut text = String::new();
    for item in spec.split(',').filter(|item| !item.is_empty()) {
        let mut parts = item.split(':');
        let Some(container) = parts.next() else { unsafe { libc::_exit(89) } };
        let Some(host) = parts.next() else { unsafe { libc::_exit(89) } };
        let Some(size) = parts.next() else { unsafe { libc::_exit(89) } };
        if parts.next().is_some() {
            unsafe { libc::_exit(89) };
        }
        text.push_str(container);
        text.push(' ');
        text.push_str(host);
        text.push(' ');
        text.push_str(size);
        text.push('\n');
    }
    text
}

#[cfg(target_os = "linux")]
fn read_byte(fd: i32) {
    let mut byte = [0u8; 1];
    loop {
        let rc = unsafe { libc::read(fd, byte.as_mut_ptr().cast(), 1) };
        if rc == 1 {
            return;
        }
        if rc < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        unsafe { libc::_exit(76) };
    }
}

#[cfg(target_os = "linux")]
fn write_byte(fd: i32) {
    let byte = [0u8; 1];
    loop {
        let rc = unsafe { libc::write(fd, byte.as_ptr().cast(), 1) };
        if rc == 1 {
            return;
        }
        if rc < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        unsafe { libc::_exit(76) };
    }
}

#[cfg(target_os = "linux")]
fn namespace_actions(parts: &[String]) -> (Vec<String>, Vec<(String, String)>) {
    let mut unshare = Vec::new();
    let mut joins = Vec::new();
    let mut saw_unshare = false;
    let mut index = 0usize;
    while index < parts.len() {
        match parts[index].as_str() {
            "--unshare" => {
                saw_unshare = true;
                let Some(value) = parts.get(index + 1) else {
                    unsafe { libc::_exit(89) };
                };
                if value != "none" {
                    unshare.extend(
                        value
                            .split(',')
                            .filter(|item| !item.is_empty())
                            .map(str::to_string),
                    );
                }
                index += 2;
            }
            "--join" => {
                let Some(value) = parts.get(index + 1) else {
                    unsafe { libc::_exit(89) };
                };
                let Some((kind, path)) = value.split_once('=') else {
                    unsafe { libc::_exit(89) };
                };
                if kind.is_empty() || path.is_empty() {
                    unsafe { libc::_exit(89) };
                }
                joins.push((kind.to_string(), path.to_string()));
                index += 2;
            }
            _ => index += 1,
        }
    }
    if !saw_unshare {
        unshare = vec!["pid".to_string(), "mount".to_string(), "uts".to_string()];
    }
    (unshare, joins)
}

#[cfg(target_os = "linux")]
fn clone_flag(kind: &str) -> libc::c_int {
    match kind {
        "mount" => libc::CLONE_NEWNS,
        "uts" => libc::CLONE_NEWUTS,
        "ipc" => libc::CLONE_NEWIPC,
        "network" => libc::CLONE_NEWNET,
        "cgroup" => libc::CLONE_NEWCGROUP,
        "pid" => libc::CLONE_NEWPID,
        _ => unsafe { libc::_exit(89) },
    }
}

#[cfg(target_os = "linux")]
fn argument_value(name: &str) -> Option<String> {
    let data = std::fs::read("/proc/self/cmdline").unwrap_or_default();
    let parts: Vec<String> = data
        .split(|byte| *byte == 0)
        .filter(|part| !part.is_empty())
        .filter_map(|part| String::from_utf8(part.to_vec()).ok())
        .collect();
    parts.windows(2).find(|pair| pair[0] == name).map(|pair| pair[1].clone())
}

#[cfg(target_os = "linux")]
fn write_pid(path: &std::ffi::CString, pid: i32) {
    unsafe {
        let fd = libc::open(path.as_ptr(), libc::O_WRONLY | libc::O_CREAT | libc::O_TRUNC, 0o644);
        if fd < 0 {
            libc::_exit(73);
        }
        let text = pid.to_string();
        let mut rest = text.as_bytes();
        while !rest.is_empty() {
            let wrote = libc::write(fd, rest.as_ptr().cast(), rest.len());
            if wrote < 0 {
                libc::_exit(73);
            }
            rest = &rest[wrote as usize..];
        }
        libc::close(fd);
    }
}

#[cfg(target_os = "linux")]
fn environ() -> *const *const libc::c_char {
    unsafe extern "C" {
        static environ: *mut *mut libc::c_char;
    }
    unsafe { environ.cast() }
}

#[cfg(target_os = "linux")]
fn enter_exec() {
    // The native image is dynamically linked. After joining the container
    // mount namespace it cannot be executed again, so this single-threaded
    // stage execs the user program directly.
    let parts = cmdline_parts();
    let mut target = None;
    let mut pid_file = None;
    let mut cwd = "/".to_string();
    let mut envs = Vec::new();
    let mut detach = false;
    let mut caps = None;
    let mut no_new_privs = false;
    let mut identity = None;
    let mut rlimits = None;
    let mut oom = None;
    let mut apparmor = None;
    let mut user = Vec::new();
    let mut index = 2usize;
    while index < parts.len() {
        match parts[index].as_str() {
            "--pid" => {
                target = parts.get(index + 1).cloned();
                index += 2;
            }
            "--pid-path" => {
                pid_file = parts.get(index + 1).cloned();
                index += 2;
            }
            "--cwd" => {
                cwd = parts.get(index + 1).cloned().unwrap_or_else(|| "/".to_string());
                index += 2;
            }
            "--env" => {
                if let Some(value) = parts.get(index + 1) {
                    envs.push(value.clone());
                }
                index += 2;
            }
            "--detach" => {
                detach = true;
                index += 1;
            }
            "--caps" => {
                caps = parts.get(index + 1).cloned();
                index += 2;
            }
            "--no-new-privs" => {
                no_new_privs = true;
                index += 1;
            }
            "--user" => {
                identity = parts.get(index + 1).cloned();
                index += 2;
            }
            "--rlimits" => {
                rlimits = parts.get(index + 1).cloned();
                index += 2;
            }
            "--oom" => {
                oom = parts.get(index + 1).cloned();
                index += 2;
            }
            "--apparmor" => {
                apparmor = parts.get(index + 1).cloned();
                index += 2;
            }
            "--" => {
                user.extend(parts[index + 1..].iter().cloned());
                break;
            }
            _ => unsafe { libc::_exit(82) },
        }
    }
    let Some(target) = target else {
        unsafe { libc::_exit(77) };
    };
    if user.is_empty() {
        unsafe { libc::_exit(82) };
    }
    let mut pipes = [0; 2];
    unsafe {
        if libc::pipe(pipes.as_mut_ptr()) != 0 {
            libc::_exit(84);
        }
        let pid = libc::fork();
        if pid < 0 {
            libc::_exit(79);
        }
        if pid > 0 {
            libc::close(pipes[1]);
            let user_pid = read_i32(pipes[0]);
            libc::close(pipes[0]);
            if let Some(pid_file) = pid_file {
                let pid_path = std::ffi::CString::new(pid_file).expect("exec pid path");
                write_pid(&pid_path, user_pid);
            }
            let status = wait_status(pid);
            libc::_exit(if detach { 0 } else { status });
        }
        libc::close(pipes[0]);
    }
    let mounts = open_namespace(&target, "mnt", false);
    let uts = open_namespace(&target, "uts", false);
    let ipc = open_namespace(&target, "ipc", false);
    let net = open_namespace(&target, "net", false);
    let cgroup = open_namespace(&target, "cgroup", false);
    let pidns = open_namespace(&target, "pid", true);
    enter_namespace(mounts, false);
    enter_namespace(uts, false);
    enter_namespace(ipc, false);
    enter_namespace(net, false);
    enter_namespace(cgroup, false);
    enter_namespace(pidns, true);
    let grand = unsafe { libc::fork() };
    if grand < 0 {
        unsafe { libc::_exit(79) };
    }
    if grand > 0 {
        let text = grand.to_string();
        write_all(pipes[1], text.as_bytes());
        unsafe { libc::close(pipes[1]) };
        if detach {
            unsafe { libc::_exit(0) };
        }
        let status = wait_status(grand);
        unsafe { libc::_exit(status) };
    }
    unsafe { libc::close(pipes[1]) };
    if cwd != "/" {
        let path = match std::ffi::CString::new(cwd) {
            Ok(value) => value,
            Err(_) => unsafe { libc::_exit(87) },
        };
        if unsafe { libc::chdir(path.as_ptr()) } != 0 {
            unsafe { libc::_exit(87) };
        }
    }
    if let Some(profile) = apparmor.as_deref() {
        set_apparmor(profile);
    }
    if let Some(spec) = caps.as_deref() {
        drop_bounding(spec);
    }
    apply_identity(oom.as_deref(), rlimits.as_deref(), identity.as_deref(), caps.is_some());
    if let Some(spec) = caps.as_deref() {
        commit_capabilities(spec);
    }
    if no_new_privs && unsafe { libc::prctl(38, 1, 0, 0, 0) } != 0 {
        unsafe { libc::_exit(91) };
    }
    if let Some(program) = user.first().cloned() {
        user[0] = resolve_program(&program, &envs);
    }
    let argv_owned: Vec<std::ffi::CString> = user
        .iter()
        .map(|arg| std::ffi::CString::new(arg.as_str()).unwrap_or_else(|_| unsafe { libc::_exit(82) }))
        .collect();
    let env_owned: Vec<std::ffi::CString> = envs
        .iter()
        .map(|arg| std::ffi::CString::new(arg.as_str()).unwrap_or_else(|_| unsafe { libc::_exit(82) }))
        .collect();
    let mut argv: Vec<*const libc::c_char> = argv_owned.iter().map(|arg| arg.as_ptr()).collect();
    argv.push(std::ptr::null());
    let mut envp: Vec<*const libc::c_char> = env_owned.iter().map(|arg| arg.as_ptr()).collect();
    envp.push(std::ptr::null());
    unsafe {
        libc::execve(argv_owned[0].as_ptr(), argv.as_ptr(), envp.as_ptr());
        libc::_exit(80);
    }
}


#[cfg(target_os = "linux")]
fn drop_bounding(spec: &str) {
    let (_, _, _, bounding, _) = capability_sets(spec);
    unsafe {
        if libc::prctl(47, 4, 0, 0, 0) != 0 {
            libc::_exit(90);
        }
        for cap in 0..64 {
            let want = ((bounding >> cap) & 1) == 1;
            let has = libc::prctl(23, cap, 0, 0, 0);
            if has < 0 {
                if want {
                    libc::_exit(90);
                }
                continue;
            }
            if has == 0 && want || (has == 1 && !want && libc::prctl(24, cap, 0, 0, 0) != 0) {
                libc::_exit(90);
            }
        }
    }
}

#[cfg(target_os = "linux")]
fn commit_capabilities(spec: &str) {
    let (permitted, inheritable, effective, _, ambient) = capability_sets(spec);
    unsafe {
        let header = [0x20080522u32, 0u32];
        let data = [
            effective as u32,
            permitted as u32,
            inheritable as u32,
            (effective >> 32) as u32,
            (permitted >> 32) as u32,
            (inheritable >> 32) as u32,
        ];
        if libc::syscall(libc::SYS_capset, header.as_ptr(), data.as_ptr()) != 0 {
            libc::_exit(90);
        }
        for cap in 0..64 {
            if ((ambient >> cap) & 1) == 1 && libc::prctl(47, 2, cap, 0, 0) != 0 {
                libc::_exit(90);
            }
        }
    }
}

#[cfg(target_os = "linux")]
fn capability_sets(spec: &str) -> (u64, u64, u64, u64, u64) {
    let parts: Vec<u64> = spec
        .split(',')
        .map(|part| u64::from_str_radix(part, 16).unwrap_or_else(|_| unsafe { libc::_exit(90) }))
        .collect();
    if parts.len() != 5 {
        unsafe { libc::_exit(90) };
    }
    (parts[0], parts[1], parts[2], parts[3], parts[4])
}

#[cfg(target_os = "linux")]
fn set_apparmor(profile: &str) {
    for path in ["/proc/self/attr/apparmor/exec", "/proc/self/attr/exec"] {
        if let Ok(mut file) = std::fs::OpenOptions::new().write(true).open(path) {
            let command = format!("exec {profile}");
            if std::io::Write::write_all(&mut file, command.as_bytes()).is_err() {
                unsafe { libc::_exit(95) };
            }
            return;
        }
    }
    unsafe { libc::_exit(95) };
}

#[cfg(target_os = "linux")]
fn apply_identity(oom: Option<&str>, rlimits: Option<&str>, identity: Option<&str>, keep_capabilities: bool) {
    if let Some(score) = oom {
        if std::fs::write("/proc/self/oom_score_adj", score).is_err() {
            unsafe { libc::_exit(92) };
        }
    }
    if let Some(spec) = rlimits {
        for item in spec.split(',').filter(|item| !item.is_empty()) {
            let Some((resource, bounds)) = item.split_once('=') else {
                unsafe { libc::_exit(93) };
            };
            let Some((soft, hard)) = bounds.split_once(':') else {
                unsafe { libc::_exit(93) };
            };
            let resource: u32 = resource.parse().unwrap_or_else(|_| unsafe { libc::_exit(93) });
            let soft: u64 = soft.parse().unwrap_or_else(|_| unsafe { libc::_exit(93) });
            let hard: u64 = hard.parse().unwrap_or_else(|_| unsafe { libc::_exit(93) });
            let value = [soft, hard];
            if unsafe { libc::setrlimit(resource, value.as_ptr().cast()) } != 0 {
                unsafe { libc::_exit(93) };
            }
        }
    }
    let Some(identity) = identity else {
        return;
    };
    let mut fields = identity.split(':');
    let uid: u32 = fields.next().unwrap_or("").parse().unwrap_or_else(|_| unsafe { libc::_exit(94) });
    let gid: u32 = fields.next().unwrap_or("").parse().unwrap_or_else(|_| unsafe { libc::_exit(94) });
    let groups: Vec<u32> = fields
        .next()
        .unwrap_or("")
        .split(',')
        .filter(|group| !group.is_empty())
        .map(|group| group.parse().unwrap_or_else(|_| unsafe { libc::_exit(94) }))
        .collect();
    unsafe {
        if keep_capabilities && uid != 0 && libc::prctl(8, 1, 0, 0, 0) != 0 {
            libc::_exit(94);
        }
        if libc::setgroups(groups.len(), groups.as_ptr()) != 0 {
            libc::_exit(94);
        }
        if libc::setresgid(gid, gid, gid) != 0 || libc::setresuid(uid, uid, uid) != 0 {
            libc::_exit(94);
        }
    }
}

#[cfg(target_os = "linux")]
fn resolve_program(program: &str, env: &[String]) -> String {
    if program.contains('/') {
        return program.to_string();
    }
    let path = env
        .iter()
        .find_map(|item| item.strip_prefix("PATH="))
        .unwrap_or("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    for dir in path.split(':') {
        if dir.is_empty() {
            continue;
        }
        let candidate = format!("{dir}/{program}");
        let Ok(path) = std::ffi::CString::new(candidate.clone()) else {
            continue;
        };
        if unsafe { libc::access(path.as_ptr(), libc::X_OK) } == 0 {
            return candidate;
        }
    }
    program.to_string()
}

#[cfg(target_os = "linux")]
fn cmdline_parts() -> Vec<String> {
    std::fs::read("/proc/self/cmdline")
        .unwrap_or_default()
        .split(|byte| *byte == 0)
        .filter(|part| !part.is_empty())
        .filter_map(|part| String::from_utf8(part.to_vec()).ok())
        .collect()
}

#[cfg(target_os = "linux")]
fn open_namespace(pid: &str, name: &str, required: bool) -> i32 {
    let path = std::ffi::CString::new(format!("/proc/{pid}/ns/{name}")).expect("namespace path");
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 && required {
        unsafe { libc::_exit(76) };
    }
    fd
}

#[cfg(target_os = "linux")]
fn enter_namespace(fd: i32, required: bool) {
    if fd < 0 {
        return;
    }
    let rc = unsafe { libc::setns(fd, 0) };
    unsafe { libc::close(fd) };
    if rc != 0 && required {
        unsafe { libc::_exit(76) };
    }
}

#[cfg(target_os = "linux")]
fn read_i32(fd: i32) -> i32 {
    let mut buf = [0u8; 32];
    let mut used = 0usize;
    unsafe {
        loop {
            if used == buf.len() {
                break;
            }
            let read = libc::read(fd, buf[used..].as_mut_ptr().cast(), buf.len() - used);
            if read < 0 {
                libc::_exit(86);
            }
            if read == 0 {
                break;
            }
            used += read as usize;
        }
    }
    let text = std::str::from_utf8(&buf[..used]).unwrap_or("");
    text.trim().parse().unwrap_or_else(|_| unsafe { libc::_exit(86) })
}

#[cfg(target_os = "linux")]
fn write_all(fd: i32, bytes: &[u8]) {
    let mut rest = bytes;
    while !rest.is_empty() {
        let wrote = unsafe { libc::write(fd, rest.as_ptr().cast(), rest.len()) };
        if wrote < 0 {
            unsafe { libc::_exit(86) };
        }
        rest = &rest[wrote as usize..];
    }
}

#[cfg(target_os = "linux")]
fn wait_status(pid: i32) -> i32 {
    let mut status = 0;
    unsafe {
        loop {
            let rc = libc::waitpid(pid, &mut status, 0);
            if rc < 0 {
                if std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                    continue;
                }
                libc::_exit(85);
            }
            break;
        }
        if libc::WIFEXITED(status) {
            libc::WEXITSTATUS(status)
        } else if libc::WIFSIGNALED(status) {
            128 + libc::WTERMSIG(status)
        } else {
            1
        }
    }
}

#[cfg(target_os = "linux")]
fn set_env(key: &str, value: &str) {
    let key = std::ffi::CString::new(key).expect("probe env key");
    let value = std::ffi::CString::new(value).expect("probe env value");
    unsafe {
        libc::setenv(key.as_ptr(), value.as_ptr(), 1);
    }
}
