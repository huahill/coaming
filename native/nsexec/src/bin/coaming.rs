fn main() {
    coaming_nsexec::keep_early_init();
    #[cfg(not(target_os = "linux"))]
    {
        eprintln!("coaming: Linux is required");
        std::process::exit(1);
    }
    #[cfg(target_os = "linux")]
    linux::run();
}

#[cfg(target_os = "linux")]
mod linux {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::process::CommandExt;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    struct Invocation {
        root: PathBuf,
        log: Option<PathBuf>,
        log_format: String,
        command: String,
        rest: Vec<String>,
    }

    pub fn run() {
        let args: Vec<String> = std::env::args().collect();
        let invocation = parse(&args);
        if invocation.command == "init" || invocation.command == "exec-init" {
            fail("namespace bootstrap did not run");
        }
        if invocation.command == "start" && invocation.rest.len() == 1 && !has_poststart(&invocation.root, &invocation.rest[0]) {
            start(&invocation);
        }
        if invocation.command == "exec" {
            if let Some(path) = &invocation.log {
                remember_log(path.clone(), invocation.log_format.clone());
            }
            run_exec(&invocation);
        }
        if invocation.command == "kill" {
            if let Some(path) = &invocation.log {
                remember_log(path.clone(), invocation.log_format.clone());
            }
            kill_container(&invocation);
        }
        exec_runtime();
    }

    fn start(invocation: &Invocation) {
        let id = &invocation.rest[0];
        let root = &invocation.root;
        if let Some(path) = &invocation.log {
            remember_log(path.clone(), invocation.log_format.clone());
        }
        let state = container_dir(root, id);
        if !state.is_dir() {
            fail(&format!("container not found: {id}"));
        }
        signal(&state.join("start.fifo"));
        wait_exec(&state.join("exec.fifo"));
        if let Err(error) = std::fs::write(state.join("status"), "running") {
            fail(&format!("writing status failed: {error}"));
        }
        std::process::exit(0);
    }

    fn signal(path: &Path) {
        let fd = open(path, libc::O_WRONLY);
        let byte = [1u8];
        let wrote = unsafe { libc::write(fd, byte.as_ptr().cast(), 1) };
        if wrote != 1 {
            fail(&format!("writing start fifo failed: {}", std::io::Error::last_os_error()));
        }
        unsafe { libc::close(fd) };
    }

    fn wait_exec(path: &Path) {
        let fd = open(path, libc::O_RDONLY);
        let mut byte = [0u8];
        loop {
            let read = unsafe { libc::read(fd, byte.as_mut_ptr().cast(), 1) };
            if read == 0 {
                unsafe { libc::close(fd) };
                return;
            }
            if read > 0 {
                if byte[0] == b'E' {
                    fail("container exec failed");
                }
                continue;
            }
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() != Some(libc::EINTR) {
                fail(&format!("reading exec fifo failed: {error}"));
            }
        }
    }

    fn open(path: &Path, flags: i32) -> i32 {
        let text = CString::new(path.as_os_str().as_bytes()).unwrap_or_else(|_| fail("path contains NUL"));
        let fd = unsafe { libc::open(text.as_ptr(), flags) };
        if fd < 0 {
            fail(&format!("open {} failed: {}", path.display(), std::io::Error::last_os_error()));
        }
        fd
    }


    fn run_exec(invocation: &Invocation) -> ! {
        let mut rest = invocation.rest.clone();
        let console = take_option(&mut rest, "--console-socket");
        let process = take_option(&mut rest, "--process");
        let pid_file = take_option(&mut rest, "--pid-file");
        let detach = take_flag(&mut rest, "--detach");
        let _ = take_option(&mut rest, "--pidfd-socket");
        let _ = take_option(&mut rest, "--preserve-fds");
        if rest.len() != 1 {
            fail("expected one container id");
        }
        let Some(process) = process else { fail("missing --process") };
        let spec = std::fs::read_to_string(&process).unwrap_or_else(|error| fail(&format!("reading process spec failed: {error}")));
        let args = super::json_string_array(&spec, "args");
        if args.is_empty() {
            fail("exec process has no args");
        }
        let cwd = super::json_string(&spec, "cwd").unwrap_or_else(|| "/".to_string());
        let env = super::json_string_array(&spec, "env");
        let id = &rest[0];
        let state = container_dir(&invocation.root, id);
        if !state.is_dir() {
            fail(&format!("container not found: {id}"));
        }
        let target = read_pid(&state.join("pid"));
        let exe = std::env::current_exe().unwrap_or_else(|error| fail(&format!("current executable: {error}")));
        let mut command = Command::new(exe);
        command.arg("exec-init");
        command.arg("--pid");
        command.arg(target.to_string());
        if let Some(path) = pid_file {
            command.arg("--pid-path");
            command.arg(path);
        }
        command.arg("--cwd");
        command.arg(cwd);
        for item in env {
            command.arg("--env");
            command.arg(item);
        }
        if detach {
            command.arg("--detach");
        }
        if let Some(path) = console {
            command.arg("--console");
            command.arg(path);
        }
        if let Some(profile) = super::json_string(&spec, "apparmorProfile") {
            if !profile.is_empty() && profile != "unconfined" {
                command.arg("--apparmor");
                command.arg(profile);
            }
        }
        if let Some(user) = super::json_user(&spec) {
            command.arg("--user");
            command.arg(user);
        }
        if let Some(caps) = super::encode_capabilities(&spec) {
            command.arg("--caps");
            command.arg(caps);
        }
        if super::json_flag(&spec, "noNewPrivileges") {
            command.arg("--no-new-privs");
        }
        let rules = super::seccomp_rules(&std::fs::read_to_string(state.join("config.json")).unwrap_or_default());
        if !rules.is_empty() {
            let path = state.join("seccomp.rules");
            std::fs::write(&path, rules.join("\n") + "\n").unwrap_or_else(|error| fail(&format!("writing seccomp rules failed: {error}")));
            command.arg("--seccomp");
            command.arg(path);
        }
        command.arg("--");
        command.args(args);
        let error = command.exec();
        fail(&format!("exec init failed: {error}"));
    }

    fn kill_container(invocation: &Invocation) {
        let mut rest = invocation.rest.clone();
        let all = take_flag(&mut rest, "--all");
        if rest.len() != 2 {
            fail("expected container id and signal");
        }
        let Some(signal) = super::signal_number(&rest[1]) else {
            fail(&format!("unknown signal {}", rest[1]));
        };
        let state = container_dir(&invocation.root, &rest[0]);
        if !state.is_dir() {
            fail(&format!("container not found: {}", rest[0]));
        }
        let mut pids = if all { cgroup_pids(&state) } else { Vec::new() };
        if pids.is_empty() {
            pids.push(read_pid(&state.join("pid")));
        }
        let mut dropped = false;
        for pid in pids {
            signal_process(pid, signal, &mut dropped);
        }
        std::process::exit(0);
    }

    fn signal_process(pid: i32, signal: i32, dropped: &mut bool) {
        loop {
            let rc = unsafe { libc::kill(pid, signal) };
            if rc == 0 {
                return;
            }
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::ESRCH) {
                return;
            }
            // containerd allows signals from peer unconfined, runc, or crun. A named
            // profile stays a distinct peer even with flags=(unconfined), so the kernel
            // returns EACCES until this process becomes unconfined.
            if error.raw_os_error() == Some(libc::EACCES) && !*dropped && become_unconfined() {
                *dropped = true;
                continue;
            }
            let code = error.raw_os_error().unwrap_or(-1);
            fail(&format!("kill {pid} failed: {code}"));
        }
    }

    fn become_unconfined() -> bool {
        for path in ["/proc/self/attr/apparmor/current", "/proc/self/attr/current"] {
            let Ok(mut file) = std::fs::OpenOptions::new().write(true).open(path) else {
                continue;
            };
            use std::io::Write;
            if file.write_all(b"changeprofile unconfined").is_ok() {
                return true;
            }
        }
        false
    }

    fn cgroup_pids(state: &Path) -> Vec<i32> {
        let Ok(cgroup) = std::fs::read_to_string(state.join("cgroup")) else {
            return Vec::new();
        };
        let cgroup = cgroup.trim();
        if cgroup.is_empty() {
            return Vec::new();
        }
        let Ok(text) = std::fs::read_to_string(Path::new(cgroup).join("cgroup.procs")) else {
            return Vec::new();
        };
        text.split_whitespace().filter_map(|item| item.parse().ok()).filter(|pid| *pid > 0).collect()
    }

    fn read_pid(path: &Path) -> i32 {
        let Ok(text) = std::fs::read_to_string(path) else {
            fail("container pid is missing");
        };
        text.trim().parse().unwrap_or_else(|_| fail("container pid is invalid"))
    }

    fn take_option(args: &mut Vec<String>, name: &str) -> Option<String> {
        let index = args.iter().position(|item| item == name)?;
        if index + 1 >= args.len() {
            fail(&format!("missing value for {name}"));
        }
        args.remove(index);
        Some(args.remove(index))
    }

    fn take_flag(args: &mut Vec<String>, name: &str) -> bool {
        if let Some(index) = args.iter().position(|item| item == name) {
            args.remove(index);
            true
        } else {
            false
        }
    }

    fn has_poststart(root: &Path, id: &str) -> bool {
        let Ok(text) = std::fs::read_to_string(container_dir(root, id).join("config.json")) else {
            return true;
        };
        text.contains("\"poststart\"")
    }

    fn container_dir(root: &Path, id: &str) -> PathBuf {
        if id.is_empty() || id.contains('/') || id.contains("..") {
            fail("invalid container id");
        }
        root.join(id)
    }

    fn parse(args: &[String]) -> Invocation {
        let mut root = PathBuf::from("/run/coaming");
        let mut log = None;
        let mut log_format = "text".to_string();
        let mut index = 1usize;
        while index < args.len() && args[index].starts_with('-') {
            let token = &args[index];
            let (option, inline) = token
                .split_once('=')
                .map(|(name, value)| (name.to_string(), Some(value.to_string())))
                .unwrap_or_else(|| (token.clone(), None));
            let value = || inline.clone().or_else(|| args.get(index + 1).cloned());
            match option.as_str() {
                "--root" => {
                    let Some(path) = value() else { fail("missing value for --root") };
                    if inline.is_none() {
                        index += 1;
                    }
                    root = PathBuf::from(path);
                }
                "--log" => {
                    let Some(path) = value() else { fail("missing value for --log") };
                    if inline.is_none() {
                        index += 1;
                    }
                    log = Some(PathBuf::from(path));
                }
                "--log-format" => {
                    let Some(format) = value() else { fail("missing value for --log-format") };
                    if inline.is_none() {
                        index += 1;
                    }
                    log_format = format;
                }
                "--rootless" => {
                    if value().is_none() {
                        fail(&format!("missing value for {option}"));
                    }
                    if inline.is_none() {
                        index += 1;
                    }
                }
                "--systemd-cgroup" | "--debug" => {}
                "--version" => exec_runtime(),
                _ => exec_runtime(),
            }
            index += 1;
        }
        if index >= args.len() {
            exec_runtime();
        }
        Invocation {
            root,
            log,
            log_format,
            command: args[index].clone(),
            rest: args[index + 1..].to_vec(),
        }
    }

    fn exec_runtime() -> ! {
        let runtime = find_runtime();
        let launcher = std::env::current_exe().unwrap_or_else(|error| fail(&format!("current executable: {error}")));
        let mut command = Command::new(&runtime);
        command.args(std::env::args().skip(1));
        command.env("COAMING_LAUNCHER", &launcher);
        command.env("COAMING_RUNTIME", &runtime);
        let error = command.exec();
        fail(&format!("exec {} failed: {error}", runtime.display()));
    }

    fn find_runtime() -> PathBuf {
        if let Some(path) = std::env::var_os("COAMING_RUNTIME").filter(|value| !value.is_empty()) {
            let path = PathBuf::from(path);
            if path.is_file() {
                return path;
            }
        }
        if let Ok(exe) = std::env::current_exe() {
            if let Some(dir) = exe.parent() {
                let sibling = dir.join("coaming-runtime");
                if sibling.is_file() {
                    return sibling;
                }
            }
        }
        fail("coaming-runtime was not found next to the launcher");
    }

    fn fail(message: &str) -> ! {
        if let Some((path, format)) = remembered_log() {
            let line = if format == "json" {
                format!("{{\"level\":\"error\",\"msg\":\"{}\"}}\n", message.replace('\\', "\\\\").replace('"', "\\\""))
            } else {
                format!("{message}\n")
            };
            let _ = std::fs::OpenOptions::new().create(true).append(true).open(path).and_then(|mut file| {
                use std::io::Write;
                file.write_all(line.as_bytes())
            });
        }
        eprintln!("coaming: {message}");
        std::process::exit(1);
    }

    fn remember_log(path: PathBuf, format: String) {
        LOG.with(|slot| *slot.borrow_mut() = Some((path, format)));
    }

    fn remembered_log() -> Option<(PathBuf, String)> {
        LOG.with(|slot| slot.borrow().clone())
    }

    thread_local! {
        static LOG: std::cell::RefCell<Option<(PathBuf, String)>> = const { std::cell::RefCell::new(None) };
    }
}



fn json_user(spec: &str) -> Option<String> {
    let object = top_level_value(spec, "user")?.trim().to_string();
    if object == "null" || !object.starts_with('{') {
        return None;
    }
    let uid = json_number(&object, "uid").unwrap_or(0);
    let gid = json_number(&object, "gid").unwrap_or(0);
    let groups = json_number_array(&object, "additionalGids");
    let extra = groups.iter().map(|value| value.to_string()).collect::<Vec<_>>().join(",");
    Some(format!("{uid}:{gid}:{extra}"))
}

fn json_number(json: &str, key: &str) -> Option<i64> {
    let raw = top_level_value(json, key)?.trim().trim_end_matches(',').to_string();
    raw.parse().ok()
}

fn json_number_array(json: &str, key: &str) -> Vec<i64> {
    let Some(raw) = top_level_value(json, key) else {
        return Vec::new();
    };
    let raw = raw.trim();
    if !raw.starts_with('[') {
        return Vec::new();
    }
    raw.trim_matches(|ch| ch == '[' || ch == ']')
        .split(',')
        .filter_map(|item| item.trim().parse().ok())
        .collect()
}


const CAPABILITIES: &[&str] = &[
    "CAP_CHOWN",
    "CAP_DAC_OVERRIDE",
    "CAP_DAC_READ_SEARCH",
    "CAP_FOWNER",
    "CAP_FSETID",
    "CAP_KILL",
    "CAP_SETGID",
    "CAP_SETUID",
    "CAP_SETPCAP",
    "CAP_LINUX_IMMUTABLE",
    "CAP_NET_BIND_SERVICE",
    "CAP_NET_BROADCAST",
    "CAP_NET_ADMIN",
    "CAP_NET_RAW",
    "CAP_IPC_LOCK",
    "CAP_IPC_OWNER",
    "CAP_SYS_MODULE",
    "CAP_SYS_RAWIO",
    "CAP_SYS_CHROOT",
    "CAP_SYS_PTRACE",
    "CAP_SYS_PACCT",
    "CAP_SYS_ADMIN",
    "CAP_SYS_BOOT",
    "CAP_SYS_NICE",
    "CAP_SYS_RESOURCE",
    "CAP_SYS_TIME",
    "CAP_SYS_TTY_CONFIG",
    "CAP_MKNOD",
    "CAP_LEASE",
    "CAP_AUDIT_WRITE",
    "CAP_AUDIT_CONTROL",
    "CAP_SETFCAP",
    "CAP_MAC_OVERRIDE",
    "CAP_MAC_ADMIN",
    "CAP_SYSLOG",
    "CAP_WAKE_ALARM",
    "CAP_BLOCK_SUSPEND",
    "CAP_AUDIT_READ",
    "CAP_PERFMON",
    "CAP_BPF",
];

fn encode_capabilities(spec: &str) -> Option<String> {
    let object = top_level_value(spec, "capabilities")?.trim().to_string();
    if !object.starts_with('{') {
        return None;
    }
    let sets = ["permitted", "inheritable", "effective", "bounding", "ambient"];
    let encoded = sets
        .iter()
        .map(|name| format!("{:x}", capability_mask(&object, name)))
        .collect::<Vec<_>>()
        .join(",");
    Some(encoded)
}

fn capability_mask(object: &str, key: &str) -> u64 {
    let mut bits = 0u64;
    for name in json_string_array(object, key) {
        if let Some(index) = CAPABILITIES.iter().position(|item| *item == name) {
            bits |= 1u64 << index;
        }
    }
    bits
}

fn json_flag(json: &str, key: &str) -> bool {
    top_level_value(json, key).is_some_and(|value| value.trim() == "true")
}

fn seccomp_rules(config: &str) -> Vec<String> {
    let Some(profile) = find_json_value(config, "seccomp") else {
        return Vec::new();
    };
    let profile = profile.trim();
    if !profile.starts_with('{') {
        return Vec::new();
    }
    let errno = json_number(profile, "errnoRet");
    let Some(default_name) = json_string(profile, "defaultAction") else {
        return Vec::new();
    };
    let mut rules = vec![format!("DEFAULT {}", seccomp_action(&default_name, errno))];
    let Some(calls) = json_value(profile, "syscalls") else {
        return rules;
    };
    for call in json_objects(calls.trim()) {
        let call_errno = json_number(&call, "errnoRet").or(errno);
        let Some(action_name) = json_string(&call, "action") else {
            continue;
        };
        let action = seccomp_action(&action_name, call_errno);
        let args = seccomp_args(&call);
        for name in json_string_array(&call, "names") {
            let mut line = format!("RULE {action} {name}");
            for arg in &args {
                line.push(' ');
                line.push_str(arg);
            }
            rules.push(line);
        }
    }
    rules
}

fn seccomp_args(call: &str) -> Vec<String> {
    let Some(raw) = json_value(call, "args") else {
        return Vec::new();
    };
    let mut encoded = Vec::new();
    for arg in json_objects(raw.trim()) {
        let index = json_number(&arg, "index").unwrap_or(0);
        let op = json_string(&arg, "op").unwrap_or_default();
        let value = json_number(&arg, "value").unwrap_or(0);
        let value_two = json_number(&arg, "valueTwo").unwrap_or(0);
        encoded.push(format!("{index} {} {value} {value_two}", seccomp_operation(&op)));
    }
    encoded
}

fn seccomp_action(name: &str, errno: Option<i64>) -> u32 {
    match name {
        "SCMP_ACT_ALLOW" => 0x7fff0000,
        "SCMP_ACT_ERRNO" => 0x00050000 | ((errno.unwrap_or(1) as u32) & 0xffff),
        "SCMP_ACT_KILL" | "SCMP_ACT_KILL_THREAD" => 0,
        "SCMP_ACT_KILL_PROCESS" => 0x80000000,
        "SCMP_ACT_TRAP" => 0x00030000,
        "SCMP_ACT_LOG" => 0x7ffc0000,
        "SCMP_ACT_NOTIFY" => 0x7fc00000,
        _ => 0x7fff0000,
    }
}

fn seccomp_operation(name: &str) -> u32 {
    match name {
        "SCMP_CMP_NE" => 1,
        "SCMP_CMP_LT" => 2,
        "SCMP_CMP_LE" => 3,
        "SCMP_CMP_EQ" => 4,
        "SCMP_CMP_GE" => 5,
        "SCMP_CMP_GT" => 6,
        "SCMP_CMP_MASKED_EQ" => 7,
        _ => 4,
    }
}

fn json_value(json: &str, key: &str) -> Option<String> {
    top_level_value(json, key).map(|value| value.trim().to_string())
}

fn find_json_value(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\"");
    let bytes = json.as_bytes();
    let mut index = 0usize;
    let mut in_string = false;
    let mut escape = false;
    while index < bytes.len() {
        let byte = bytes[index];
        if in_string {
            if escape {
                escape = false;
            } else if byte == b'\\' {
                escape = true;
            } else if byte == b'"' {
                in_string = false;
            }
            index += 1;
            continue;
        }
        if byte == b'"' && json[index..].starts_with(&needle) {
            let mut cursor = index + needle.len();
            while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                cursor += 1;
            }
            if cursor < bytes.len() && bytes[cursor] == b':' {
                cursor += 1;
                while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                    cursor += 1;
                }
                let end = value_end(json, cursor)?;
                return Some(json[cursor..end].trim().to_string());
            }
        }
        if byte == b'"' {
            in_string = true;
        }
        index += 1;
    }
    None
}

fn json_objects(value: &str) -> Vec<String> {
    let bytes = value.as_bytes();
    let mut objects = Vec::new();
    let mut index = 0usize;
    while index < bytes.len() {
        if bytes[index] == b'{' {
            let Some(end) = value_end(value, index) else {
                break;
            };
            objects.push(value[index..end].to_string());
            index = end;
            continue;
        }
        index += 1;
    }
    objects
}

fn json_string(json: &str, key: &str) -> Option<String> {
    let raw = top_level_value(json, key)?;
    let raw = raw.trim();
    if !raw.starts_with('"') {
        return None;
    }
    parse_json_string(raw).map(|(value, _)| value)
}

fn json_string_array(json: &str, key: &str) -> Vec<String> {
    let Some(raw) = top_level_value(json, key) else {
        return Vec::new();
    };
    let raw = raw.trim();
    if !raw.starts_with('[') {
        return Vec::new();
    }
    let mut values = Vec::new();
    let bytes = raw.as_bytes();
    let mut index = 1usize;
    while index < bytes.len() {
        while index < bytes.len() && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index >= bytes.len() || bytes[index] == b']' {
            break;
        }
        if bytes[index] == b',' {
            index += 1;
            continue;
        }
        if bytes[index] != b'"' {
            break;
        }
        let Some((value, next)) = parse_json_string(&raw[index..]) else {
            break;
        };
        values.push(value);
        index += next;
    }
    values
}

fn top_level_value<'a>(json: &'a str, key: &str) -> Option<&'a str> {
    let needle = format!("\"{key}\"");
    let bytes = json.as_bytes();
    let mut depth = 0i32;
    let mut index = 0usize;
    let mut in_string = false;
    let mut escape = false;
    while index < bytes.len() {
        let byte = bytes[index];
        if in_string {
            if escape {
                escape = false;
            } else if byte == b'\\' {
                escape = true;
            } else if byte == b'"' {
                in_string = false;
            }
            index += 1;
            continue;
        }
        match byte {
            b'"' => {
                if depth == 1 && json[index..].starts_with(&needle) {
                    let mut cursor = index + needle.len();
                    while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                        cursor += 1;
                    }
                    if cursor < bytes.len() && bytes[cursor] == b':' {
                        cursor += 1;
                        while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                            cursor += 1;
                        }
                        let end = value_end(json, cursor)?;
                        return Some(&json[cursor..end]);
                    }
                }
                in_string = true;
            }
            b'{' | b'[' => depth += 1,
            b'}' | b']' => depth -= 1,
            _ => {}
        }
        index += 1;
    }
    None
}

fn value_end(json: &str, start: usize) -> Option<usize> {
    let bytes = json.as_bytes();
    if start >= bytes.len() {
        return None;
    }
    if bytes[start] == b'"' {
        return parse_json_string(&json[start..]).map(|(_, next)| start + next);
    }
    let mut depth = 0i32;
    let mut in_string = false;
    let mut escape = false;
    for (offset, byte) in bytes[start..].iter().enumerate() {
        if in_string {
            if escape {
                escape = false;
            } else if *byte == b'\\' {
                escape = true;
            } else if *byte == b'"' {
                in_string = false;
            }
            continue;
        }
        match byte {
            b'"' => in_string = true,
            b'{' | b'[' => depth += 1,
            b'}' | b']' => {
                if depth == 0 {
                    return Some(start + offset);
                }
                depth -= 1;
            }
            b',' if depth == 0 => return Some(start + offset),
            _ => {}
        }
    }
    Some(json.len())
}

fn parse_json_string(json: &str) -> Option<(String, usize)> {
    let bytes = json.as_bytes();
    if bytes.first() != Some(&b'"') {
        return None;
    }
    let mut value = String::new();
    let mut index = 1usize;
    while index < bytes.len() {
        let byte = bytes[index];
        if byte == b'"' {
            return Some((value, index + 1));
        }
        if byte != b'\\' {
            value.push(byte as char);
            index += 1;
            continue;
        }
        index += 1;
        if index >= bytes.len() {
            return None;
        }
        match bytes[index] {
            b'"' => value.push('"'),
            b'\\' => value.push('\\'),
            b'/' => value.push('/'),
            b'n' => value.push('\n'),
            b'r' => value.push('\r'),
            b't' => value.push('\t'),
            b'u' => {
                if index + 4 >= bytes.len() {
                    return None;
                }
                let hex = std::str::from_utf8(&bytes[index + 1..index + 5]).ok()?;
                let code = u32::from_str_radix(hex, 16).ok()?;
                value.push(char::from_u32(code)?);
                index += 4;
            }
            other => value.push(other as char),
        }
        index += 1;
    }
    None
}

fn signal_number(signal: &str) -> Option<i32> {
    let upper = signal.to_ascii_uppercase();
    let name = upper.strip_prefix("SIG").unwrap_or(&upper);
    Some(match name {
        "HUP" => 1,
        "INT" => 2,
        "QUIT" => 3,
        "ILL" => 4,
        "TRAP" => 5,
        "ABRT" => 6,
        "BUS" => 7,
        "FPE" => 8,
        "KILL" => 9,
        "USR1" => 10,
        "SEGV" => 11,
        "USR2" => 12,
        "PIPE" => 13,
        "ALRM" => 14,
        "TERM" => 15,
        "CHLD" => 17,
        "CONT" => 18,
        "STOP" => 19,
        "TSTP" => 20,
        "TTIN" => 21,
        "TTOU" => 22,
        "URG" => 23,
        "XCPU" => 24,
        "XFSZ" => 25,
        "VTALRM" => 26,
        "PROF" => 27,
        "WINCH" => 28,
        "IO" => 29,
        "PWR" => 30,
        "SYS" => 31,
        _ => return signal.parse().ok(),
    })
}

#[cfg(test)]
mod signal_tests {
    use super::{encode_capabilities, json_flag, json_string, json_string_array, json_user, seccomp_rules, signal_number};

    #[test]
    fn named_and_numeric_signals_match_the_runtime() {
        assert_eq!(signal_number("TERM"), Some(15));
        assert_eq!(signal_number("SIGKILL"), Some(9));
        assert_eq!(signal_number("15"), Some(15));
        assert_eq!(signal_number("SIG15"), None);
        assert_eq!(signal_number("nope"), None);
    }

    #[test]
    fn process_spec_fields_come_from_the_top_level() {
        let spec = r#"{"terminal":true,"args":["/bin/echo","hi"],"cwd":"/tmp","env":["PATH=/bin"],"user":{"args":["no"]}}"#;
        assert_eq!(json_string_array(spec, "args"), vec!["/bin/echo", "hi"]);
        assert_eq!(json_string(spec, "cwd").as_deref(), Some("/tmp"));
        assert_eq!(json_string_array(spec, "env"), vec!["PATH=/bin"]);
    }

    #[test]
    fn exec_user_keeps_supplemental_groups() {
        let spec = r#"{"args":["id"],"user":{"uid":1001,"gid":1001,"additionalGids":[10,1234]}}"#;
        assert_eq!(json_user(spec).as_deref(), Some("1001:1001:10,1234"));
        let caps = r#"{"capabilities":{"permitted":["CAP_CHOWN","CAP_NET_ADMIN"],"effective":["CAP_CHOWN"],"inheritable":[],"bounding":["CAP_CHOWN"],"ambient":[]},"noNewPrivileges":true}"#;
        assert_eq!(encode_capabilities(caps).as_deref(), Some("1001,0,1,1,0"));
        assert!(json_flag(caps, "noNewPrivileges"));
        let config = r#"{"linux":{"seccomp":{"defaultAction":"SCMP_ACT_ERRNO","errnoRet":1,"syscalls":[{"names":["chmod"],"action":"SCMP_ACT_ERRNO","errnoRet":1}]}}}"#;
        assert_eq!(seccomp_rules(config), vec!["DEFAULT 327681", "RULE 327681 chmod"]);
        let script = r#"{"args":["sh","-c","while [ ! -f /var/run/nginx.pid ]; do sleep 1; done"]}"#;
        assert_eq!(
            json_string_array(script, "args"),
            vec!["sh", "-c", "while [ ! -f /var/run/nginx.pid ]; do sleep 1; done"]
        );
        let encoded = r#"{"args":["sh","-c","done\u0026\u0026 cat"]}"#;
        assert_eq!(json_string_array(encoded, "args"), vec!["sh", "-c", "done&& cat"]);
    }
}
