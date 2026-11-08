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
    set_env("COAMING_INIT_STAGE", "jvm");
    let flags = libc::CLONE_NEWNS | libc::CLONE_NEWUTS | libc::CLONE_NEWPID;
    unsafe {
        if libc::unshare(flags) != 0 {
            libc::_exit(70);
        }
        let pid = libc::fork();
        if pid < 0 {
            libc::_exit(71);
        }
        if pid > 0 {
            write_pid(&pid_path, pid);
            libc::_exit(0);
        }
        let mut argv: Vec<*const libc::c_char> = args.iter().map(|arg| arg.as_ptr()).collect();
        argv.push(std::ptr::null());
        libc::execve(args[0].as_ptr(), argv.as_ptr(), environ());
        libc::_exit(72);
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
fn set_env(key: &str, value: &str) {
    let key = std::ffi::CString::new(key).expect("probe env key");
    let value = std::ffi::CString::new(value).expect("probe env value");
    unsafe {
        libc::setenv(key.as_ptr(), value.as_ptr(), 1);
    }
}
