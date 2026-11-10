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
    let (unshare, joins) = namespace_actions(&cmdline_parts());
    set_env("COAMING_INIT_STAGE", "jvm");
    let mut flags: libc::c_int = 0;
    let mut new_pid = false;
    for kind in &unshare {
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
            if libc::setns(fd, 0) != 0 {
                libc::_exit(88);
            }
            libc::close(fd);
        }
        if new_pid && libc::unshare(libc::CLONE_NEWPID) != 0 {
            libc::_exit(70);
        }
        if pid_join >= 0 && libc::setns(pid_join, 0) != 0 {
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
        if mount_join >= 0 && libc::setns(mount_join, 0) != 0 {
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
    let Some(pid_file) = pid_file else {
        unsafe { libc::_exit(78) };
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
            let pid_path = std::ffi::CString::new(pid_file).expect("exec pid path");
            write_pid(&pid_path, user_pid);
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
    if let Some(spec) = caps {
        apply_capabilities(&spec);
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
fn apply_capabilities(spec: &str) {
    let parts: Vec<u64> = spec
        .split(',')
        .map(|part| u64::from_str_radix(part, 16).unwrap_or_else(|_| unsafe { libc::_exit(90) }))
        .collect();
    if parts.len() != 5 {
        unsafe { libc::_exit(90) };
    }
    let (permitted, inheritable, effective, bounding, ambient) = (parts[0], parts[1], parts[2], parts[3], parts[4]);
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
            if has == 0 && want {
                libc::_exit(90);
            }
            if has == 1 && !want && libc::prctl(24, cap, 0, 0, 0) != 0 {
                libc::_exit(90);
            }
        }
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
