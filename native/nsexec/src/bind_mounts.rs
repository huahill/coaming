/// Host bind mounts have to be opened before entering a joined user namespace.
/// Containerd keeps their sources under directories mode 0700, and the mapped
/// container root cannot walk those paths.
#[derive(Debug, PartialEq, Eq)]
pub struct PreparedBind {
    pub destination: String,
    pub source: String,
    pub recursive: bool,
    pub attr_set: u64,
    pub propagation: u64,
}

const MOUNT_ATTR_RDONLY: u64 = 0x1;
const MOUNT_ATTR_NOSUID: u64 = 0x2;
const MOUNT_ATTR_NODEV: u64 = 0x4;
const MOUNT_ATTR_NOEXEC: u64 = 0x8;
const MOUNT_ATTR_NOATIME: u64 = 0x10;
const MOUNT_ATTR_STRICTATIME: u64 = 0x20;
const MOUNT_ATTR_NODIRATIME: u64 = 0x80;
const MS_REC: u64 = 16384;
const MS_UNBINDABLE: u64 = 1 << 17;
const MS_PRIVATE: u64 = 1 << 18;
const MS_SLAVE: u64 = 1 << 19;
const MS_SHARED: u64 = 1 << 20;

pub fn bind_mounts(json: &str) -> Vec<PreparedBind> {
    let Some(array) = array_after_key(json, "mounts") else {
        return Vec::new();
    };
    let mut found = Vec::new();
    for object in objects(array) {
        let Some(destination) = string_field(object, "destination") else {
            continue;
        };
        let Some(source) = string_field(object, "source") else {
            continue;
        };
        if !source.starts_with('/') {
            continue;
        }
        let kind = string_field(object, "type").unwrap_or_default();
        let options = string_array_field(object, "options");
        let recursive = kind == "rbind" || options.iter().any(|option| option == "rbind");
        let bind = recursive || kind == "bind" || options.iter().any(|option| option == "bind");
        let idmapped = options.iter().any(|option| option == "idmap" || option == "ridmap");
        if bind && !idmapped {
            found.push(prepare_bind(destination, source, recursive, &options));
        }
    }
    found
}

fn prepare_bind(destination: String, source: String, mut recursive: bool, options: &[String]) -> PreparedBind {
    let mut attr_set = 0u64;
    let mut propagation = 0u64;
    let mut saw_propagation = false;
    for option in options {
        match option.as_str() {
            "rbind" => recursive = true,
            "ro" => attr_set |= MOUNT_ATTR_RDONLY,
            "rw" => attr_set &= !MOUNT_ATTR_RDONLY,
            "nosuid" => attr_set |= MOUNT_ATTR_NOSUID,
            "nodev" => attr_set |= MOUNT_ATTR_NODEV,
            "noexec" => attr_set |= MOUNT_ATTR_NOEXEC,
            "noatime" => attr_set |= MOUNT_ATTR_NOATIME,
            "strictatime" => attr_set |= MOUNT_ATTR_STRICTATIME,
            "nodiratime" => attr_set |= MOUNT_ATTR_NODIRATIME,
            "private" => {
                propagation = MS_PRIVATE;
                saw_propagation = true;
            }
            "rprivate" => {
                propagation = MS_REC | MS_PRIVATE;
                saw_propagation = true;
            }
            "shared" => {
                propagation = MS_SHARED;
                saw_propagation = true;
            }
            "rshared" => {
                propagation = MS_REC | MS_SHARED;
                saw_propagation = true;
            }
            "slave" => {
                propagation = MS_SLAVE;
                saw_propagation = true;
            }
            "rslave" => {
                propagation = MS_REC | MS_SLAVE;
                saw_propagation = true;
            }
            "unbindable" => {
                propagation = MS_UNBINDABLE;
                saw_propagation = true;
            }
            "runbindable" => {
                propagation = MS_REC | MS_UNBINDABLE;
                saw_propagation = true;
            }
            _ => {}
        }
    }
    if !saw_propagation {
        propagation = MS_REC | MS_PRIVATE;
    }
    PreparedBind {
        destination,
        source,
        recursive,
        attr_set,
        propagation,
    }
}

fn array_after_key<'a>(json: &'a str, key: &str) -> Option<&'a str> {
    let bytes = json.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'"' {
            let (text, next) = parse_string(json, index)?;
            if text == key {
                let mut cursor = next;
                while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                    cursor += 1;
                }
                if bytes.get(cursor) == Some(&b':') {
                    cursor += 1;
                    while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                        cursor += 1;
                    }
                    if bytes.get(cursor) == Some(&b'[') {
                        let end = matching_bracket(json, cursor, b'[', b']')?;
                        return Some(&json[cursor + 1..end]);
                    }
                }
            }
            index = next;
            continue;
        }
        index += 1;
    }
    None
}

fn objects(array: &str) -> Vec<&str> {
    let bytes = array.as_bytes();
    let mut index = 0;
    let mut found = Vec::new();
    while index < bytes.len() {
        match bytes[index] {
            b'"' => match parse_string(array, index) {
                Some((_, next)) => index = next,
                None => break,
            },
            b'{' => match matching_bracket(array, index, b'{', b'}') {
                Some(end) => {
                    found.push(&array[index..=end]);
                    index = end + 1;
                }
                None => break,
            },
            _ => index += 1,
        }
    }
    found
}

fn string_field(object: &str, key: &str) -> Option<String> {
    let start = field_value(object, key)?;
    parse_string(object, start).map(|(value, _)| value)
}

fn string_array_field(object: &str, key: &str) -> Vec<String> {
    let Some(start) = field_value(object, key) else {
        return Vec::new();
    };
    if object.as_bytes().get(start) != Some(&b'[') {
        return Vec::new();
    }
    let Some(end) = matching_bracket(object, start, b'[', b']') else {
        return Vec::new();
    };
    let mut values = Vec::new();
    let mut index = start + 1;
    let inner = object.as_bytes();
    while index < end {
        if inner[index] == b'"' {
            let Some((value, next)) = parse_string(object, index) else {
                break;
            };
            values.push(value);
            index = next;
            continue;
        }
        index += 1;
    }
    values
}

fn field_value(object: &str, key: &str) -> Option<usize> {
    let bytes = object.as_bytes();
    let mut index = 0;
    let mut depth = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'"' => {
                let (text, next) = parse_string(object, index)?;
                if depth == 1 && text == key {
                    let mut cursor = next;
                    while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                        cursor += 1;
                    }
                    if bytes.get(cursor) != Some(&b':') {
                        return None;
                    }
                    cursor += 1;
                    while cursor < bytes.len() && bytes[cursor].is_ascii_whitespace() {
                        cursor += 1;
                    }
                    return Some(cursor);
                }
                index = next;
            }
            b'{' | b'[' => {
                depth += 1;
                index += 1;
            }
            b'}' | b']' => {
                depth -= 1;
                index += 1;
            }
            _ => index += 1,
        }
    }
    None
}

fn matching_bracket(json: &str, start: usize, open: u8, close: u8) -> Option<usize> {
    let bytes = json.as_bytes();
    let mut depth = 0;
    let mut index = start;
    while index < bytes.len() {
        if bytes[index] == b'"' {
            let (_, next) = parse_string(json, index)?;
            index = next;
            continue;
        }
        if bytes[index] == open {
            depth += 1;
        } else if bytes[index] == close {
            depth -= 1;
            if depth == 0 {
                return Some(index);
            }
        }
        index += 1;
    }
    None
}

fn parse_string(json: &str, start: usize) -> Option<(String, usize)> {
    let bytes = json.as_bytes();
    if bytes.get(start) != Some(&b'"') {
        return None;
    }
    let mut raw = Vec::new();
    let mut index = start + 1;
    while index < bytes.len() {
        match bytes[index] {
            b'\\' => {
                let next = *bytes.get(index + 1)?;
                match next {
                    b'"' | b'\\' | b'/' => raw.push(next),
                    b'n' => raw.push(b'\n'),
                    b't' => raw.push(b'\t'),
                    b'r' => raw.push(b'\r'),
                    b'u' => {
                        let hex = json.get(index + 2..index + 6)?;
                        let code = u32::from_str_radix(hex, 16).ok()?;
                        let ch = char::from_u32(code)?;
                        raw.extend_from_slice(ch.encode_utf8(&mut [0; 4]).as_bytes());
                        index += 6;
                        continue;
                    }
                    _ => return None,
                }
                index += 2;
            }
            b'"' => return Some((String::from_utf8(raw).ok()?, index + 1)),
            byte => {
                raw.push(byte);
                index += 1;
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::bind_mounts;

    #[test]
    fn selects_host_bind_mounts_and_skips_idmap() {
        let json = r#"{
          "mounts": [
            {"destination": "/proc", "type": "proc", "source": "proc", "options": ["nosuid"]},
            {"destination": "/dev/shm", "type": "bind", "source": "/run/shm", "options": ["rbind", "ro"]},
            {"destination": "/etc/resolv.conf", "type": "bind", "source": "/var/lib/resolv.conf", "options": ["rbind", "nosuid"]},
            {"destination": "/data", "type": "bind", "source": "/home/data", "options": ["bind", "idmap"]},
            {"destination": "/note", "type": "bind", "source": "/tmp/quote\"x", "options": ["bind"]}
          ],
          "linux": {"maskedPaths": ["/proc/acpi"]}
        }"#;

        let mounts = bind_mounts(json);
        assert_eq!(mounts[0].destination, "/dev/shm");
        assert_eq!(mounts[0].source, "/run/shm");
        assert!(mounts[0].recursive);
        assert_eq!(mounts[0].attr_set, 0x1);
        assert_eq!(mounts[0].propagation, 16384 | (1 << 18));
        assert_eq!(mounts[1].destination, "/etc/resolv.conf");
        assert_eq!(mounts[1].attr_set, 0x2);
        assert_eq!(mounts[2].destination, "/note");
        assert_eq!(mounts[2].source, "/tmp/quote\"x");
        assert!(!mounts[2].recursive);
        assert_eq!(mounts.len(), 3);
    }
}
