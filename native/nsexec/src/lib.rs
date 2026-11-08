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
    if std::env::var_os("COAMING_PROBE_FAIL").is_some_and(|value| value == "1") {
        unsafe { libc::_exit(81) };
    }
    let threads = thread_count().unwrap_or(0);
    let rc = coaming_nsexec_probe();
    set_env("COAMING_PROBE_RC", &rc.to_string());
    set_env("COAMING_PROBE_THREADS", &threads.to_string());
}

#[cfg(target_os = "linux")]
fn set_env(key: &str, value: &str) {
    let key = std::ffi::CString::new(key).expect("probe env key");
    let value = std::ffi::CString::new(value).expect("probe env value");
    unsafe {
        libc::setenv(key.as_ptr(), value.as_ptr(), 1);
    }
}
