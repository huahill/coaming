use std::process::Command;

#[test]
fn child_probe_reports_host_os() {
    let exe = env!("CARGO_BIN_EXE_probe");
    let output = Command::new(exe).output().expect("spawn probe");
    let code = output.status.code().expect("probe exit code");
    let stderr = String::from_utf8_lossy(&output.stderr);
    if cfg!(target_os = "linux") {
        assert_eq!(code, 0, "{stderr}");
    } else {
        assert_eq!(code, 1, "{stderr}");
    }
}

#[test]
fn in_process_probe_rejects_a_multithreaded_process() {
    let rc = coaming_nsexec::coaming_nsexec_probe();
    if cfg!(target_os = "linux") {
        assert_eq!(rc, 2);
    } else {
        assert_eq!(rc, 1);
    }
}
