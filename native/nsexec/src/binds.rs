#[derive(Debug, PartialEq, Eq)]
pub struct PreparedBind {
    pub destination: String,
    pub source: String,
    pub recursive: bool,
    pub attr_set: u64,
    pub recursive_readonly: bool,
}

pub fn from_arguments(parts: &[String]) -> Result<Vec<PreparedBind>, ()> {
    let mut found = Vec::new();
    let mut index = 0;
    while index < parts.len() {
        if parts[index] != "--bind" {
            index += 1;
            continue;
        }
        if index + 5 >= parts.len() {
            return Err(());
        }
        let recursive = match parts[index + 3].as_str() {
            "1" => true,
            "0" => false,
            _ => return Err(()),
        };
        let attr_set = parts[index + 4].parse::<u64>().map_err(|_| ())?;
        let recursive_readonly = match parts[index + 5].as_str() {
            "1" => true,
            "0" => false,
            _ => return Err(()),
        };
        found.push(PreparedBind {
            source: parts[index + 1].clone(),
            destination: parts[index + 2].clone(),
            recursive,
            attr_set,
            recursive_readonly,
        });
        index += 6;
    }
    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::from_arguments;

    #[test]
    fn reads_bind_arguments_and_ignores_the_rest() {
        let parts = vec![
            "coaming".to_string(),
            "init".to_string(),
            "--bind".to_string(),
            "/run/shm".to_string(),
            "/dev/shm".to_string(),
            "1".to_string(),
            "1".to_string(),
            "0".to_string(),
            "--uid-map".to_string(),
            "0:1000:1".to_string(),
        ];
        let mounts = from_arguments(&parts).unwrap();
        assert_eq!(mounts.len(), 1);
        assert_eq!(mounts[0].source, "/run/shm");
        assert_eq!(mounts[0].destination, "/dev/shm");
        assert!(mounts[0].recursive);
        assert_eq!(mounts[0].attr_set, 1);
        assert!(!mounts[0].recursive_readonly);
    }
}
