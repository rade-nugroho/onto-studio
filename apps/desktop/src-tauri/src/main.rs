#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::VecDeque;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

use tauri::{Manager, State};
use tauri_plugin_dialog::{DialogExt, MessageDialogKind};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(20);
const MAX_STDERR_LINES: usize = 50;

#[derive(Clone, serde::Serialize)]
struct EngineInfo {
    port: u16,
    token: String,
}

struct Engine {
    info: EngineInfo,
    child: Mutex<Child>,
    stderr_tail: Arc<Mutex<VecDeque<String>>>,
}

fn format_stderr_tail(stderr_tail: &Arc<Mutex<VecDeque<String>>>) -> String {
    let Ok(lines) = stderr_tail.lock() else {
        return String::new();
    };
    if lines.is_empty() {
        return String::new();
    }
    let joined: Vec<&str> = lines.iter().map(String::as_str).collect();
    format!("\n\nEngine output:\n{}", joined.join("\n"))
}

fn terminate_child(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

#[tauri::command]
fn engine_info(state: State<Engine>) -> Result<EngineInfo, String> {
    let mut child = state
        .child
        .lock()
        .map_err(|_| "Engine state lock poisoned".to_string())?;
    match child.try_wait() {
        Ok(Some(status)) => {
            let tail = format_stderr_tail(&state.stderr_tail);
            Err(format!(
                "Ontology engine exited unexpectedly ({status}){tail}"
            ))
        }
        Ok(None) => Ok(state.info.clone()),
        Err(err) => Err(format!("Failed to check ontology engine status: {err}")),
    }
}

fn parse_handshake(line: &str) -> Result<EngineInfo, String> {
    let value: serde_json::Value =
        serde_json::from_str(line.trim()).map_err(|error| error.to_string())?;
    let raw_port = value["port"]
        .as_u64()
        .ok_or("handshake is missing a port")?;
    let port = u16::try_from(raw_port)
        .ok()
        .filter(|&p| p > 0)
        .ok_or_else(|| format!("handshake port out of range: {raw_port}"))?;
    let token = value["token"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or("handshake is missing a token")?
        .to_string();
    Ok(EngineInfo { port, token })
}

fn resolve_engine_paths(
    env_jar: Option<PathBuf>,
    resource_dir: Option<PathBuf>,
    is_debug: bool,
) -> Result<(PathBuf, PathBuf), String> {
    if let Some(jar) = env_jar {
        return Ok((PathBuf::from("java"), jar));
    }
    if let Some(res_dir) = resource_dir {
        let bundled_jar = res_dir.join("resources/engine.jar");
        let bundled_java = res_dir.join("resources/jre/bin/java");
        if bundled_jar.exists() && bundled_java.exists() {
            return Ok((bundled_java, bundled_jar));
        }
    }
    if is_debug {
        let dev_jar = PathBuf::from(format!(
            "{}/../../../services/engine/target/engine.jar",
            env!("CARGO_MANIFEST_DIR")
        ));
        return Ok((PathBuf::from("java"), dev_jar));
    }
    Err("Bundled ontology engine not found in application resources".to_string())
}

/// Prefers the bundled runtime and jar, then falls back to a development build in debug mode.
fn resolve_engine(app: &tauri::AppHandle) -> Result<(PathBuf, PathBuf), String> {
    let env_jar = std::env::var_os("ONTOSTUDIO_ENGINE_JAR")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from);
    let resource_dir = app.path().resource_dir().ok();
    resolve_engine_paths(env_jar, resource_dir, cfg!(debug_assertions))
}

fn spawn_engine(app: &tauri::AppHandle) -> Result<Engine, String> {
    let (java, jar) = resolve_engine(app)?;
    if !jar.exists() {
        return Err(format!("Engine JAR not found at {}", jar.display()));
    }

    let mut child = Command::new(&java)
        .arg("-jar")
        .arg(&jar)
        .arg("--port")
        .arg("0")
        .arg("--watch-parent")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| format!("Failed to start engine at {}: {error}", jar.display()))?;

    let stderr_tail = Arc::new(Mutex::new(VecDeque::with_capacity(MAX_STDERR_LINES)));
    if let Some(stderr) = child.stderr.take() {
        let tail_ref = Arc::clone(&stderr_tail);
        std::thread::spawn(move || {
            let reader = BufReader::new(stderr);
            for line in reader.lines().map_while(Result::ok) {
                eprintln!("[engine] {line}");
                if let Ok(mut buf) = tail_ref.lock() {
                    if buf.len() >= MAX_STDERR_LINES {
                        buf.pop_front();
                    }
                    buf.push_back(line);
                }
            }
        });
    }

    let Some(stdout) = child.stdout.take() else {
        terminate_child(&mut child);
        return Err("Failed to capture engine stdout".to_string());
    };

    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let mut line = String::new();
        let res = BufReader::new(stdout)
            .read_line(&mut line)
            .map(|_| line)
            .map_err(|err| format!("Failed to read engine handshake: {err}"));
        let _ = tx.send(res);
    });

    let line = match rx.recv_timeout(HANDSHAKE_TIMEOUT) {
        Ok(Ok(line)) if !line.trim().is_empty() => line,
        Ok(Ok(_)) => {
            terminate_child(&mut child);
            let tail = format_stderr_tail(&stderr_tail);
            return Err(format!("Engine exited before sending handshake{tail}"));
        }
        Ok(Err(err)) => {
            terminate_child(&mut child);
            let tail = format_stderr_tail(&stderr_tail);
            return Err(format!("{err}{tail}"));
        }
        Err(mpsc::RecvTimeoutError::Timeout) => {
            terminate_child(&mut child);
            let tail = format_stderr_tail(&stderr_tail);
            return Err(format!(
                "Timed out waiting {}s for engine handshake{tail}",
                HANDSHAKE_TIMEOUT.as_secs()
            ));
        }
        Err(mpsc::RecvTimeoutError::Disconnected) => {
            terminate_child(&mut child);
            let tail = format_stderr_tail(&stderr_tail);
            return Err(format!("Engine handshake reader disconnected{tail}"));
        }
    };

    let info = match parse_handshake(&line) {
        Ok(info) => info,
        Err(err) => {
            terminate_child(&mut child);
            let tail = format_stderr_tail(&stderr_tail);
            return Err(format!("Invalid engine handshake ({err}){tail}"));
        }
    };

    Ok(Engine {
        info,
        child: Mutex::new(child),
        stderr_tail,
    })
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| match spawn_engine(app.handle()) {
            Ok(engine) => {
                app.manage(engine);
                Ok(())
            }
            Err(err) => {
                app.dialog()
                    .message(format!(
                        "Onto Studio failed to start the ontology engine:\n\n{err}"
                    ))
                    .title("Onto Studio Startup Error")
                    .kind(MessageDialogKind::Error)
                    .blocking_show();
                std::process::exit(1);
            }
        })
        .invoke_handler(tauri::generate_handler![engine_info])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app_handle, event| {
            if let tauri::RunEvent::Exit = event {
                if let Some(engine) = app_handle.try_state::<Engine>() {
                    if let Ok(mut child) = engine.child.lock() {
                        terminate_child(&mut child);
                    }
                }
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn unique_temp_dir(label: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let dir = std::env::temp_dir().join(format!(
            "onto-studio-test-{label}-{}-{nanos}",
            std::process::id()
        ));
        fs::create_dir_all(&dir).expect("create temp dir");
        dir
    }

    #[test]
    fn parses_handshake_line() {
        let info = parse_handshake("{\"port\":1234,\"token\":\"abc\"}").expect("valid handshake");
        assert_eq!(info.port, 1234);
        assert_eq!(info.token, "abc");
    }

    #[test]
    fn rejects_malformed_handshake() {
        assert!(parse_handshake("not json").is_err());
        assert!(parse_handshake("{\"token\":\"abc\"}").is_err());
        assert!(parse_handshake("{\"port\":1234}").is_err());
        assert!(parse_handshake("{\"port\":1234,\"token\":\"\"}").is_err());
    }

    #[test]
    fn rejects_out_of_bounds_handshake_ports() {
        assert!(parse_handshake("{\"port\":0,\"token\":\"abc\"}").is_err());
        assert!(parse_handshake("{\"port\":65536,\"token\":\"abc\"}").is_err());
        assert!(parse_handshake("{\"port\":18446744073709551615,\"token\":\"abc\"}").is_err());
        assert!(parse_handshake("{\"port\":-1,\"token\":\"abc\"}").is_err());
    }

    #[test]
    fn accepts_max_valid_handshake_port() {
        let info = parse_handshake("{\"port\":65535,\"token\":\"abc\"}").expect("max valid port");
        assert_eq!(info.port, 65535);
    }

    #[test]
    fn resolve_engine_prefers_env_override() {
        let env_jar = PathBuf::from("/custom/engine.jar");
        let (java, jar) = resolve_engine_paths(Some(env_jar.clone()), None, false)
            .expect("env override should succeed");
        assert_eq!(java, PathBuf::from("java"));
        assert_eq!(jar, env_jar);
    }

    #[test]
    fn resolve_engine_uses_bundled_resources_when_present() {
        let temp_root = unique_temp_dir("bundled");
        let res_dir = temp_root.join("resources");
        let jre_bin = res_dir.join("jre/bin");
        fs::create_dir_all(&jre_bin).expect("create jre/bin");
        let bundled_jar = res_dir.join("engine.jar");
        let bundled_java = jre_bin.join("java");
        fs::write(&bundled_jar, b"jar").expect("write jar");
        fs::write(&bundled_java, b"java").expect("write java");

        let (java, jar) = resolve_engine_paths(None, Some(temp_root.clone()), false)
            .expect("bundled resources should succeed");
        assert_eq!(java, bundled_java);
        assert_eq!(jar, bundled_jar);

        let _ = fs::remove_dir_all(&temp_root);
    }

    #[test]
    fn resolve_engine_falls_back_to_dev_jar_in_debug() {
        let (java, jar) =
            resolve_engine_paths(None, None, true).expect("debug fallback should succeed");
        assert_eq!(java, PathBuf::from("java"));
        assert!(jar.ends_with("services/engine/target/engine.jar"));
    }

    #[test]
    fn resolve_engine_errors_in_release_when_bundle_missing() {
        let empty_dir = unique_temp_dir("empty-release");
        let err = resolve_engine_paths(None, Some(empty_dir.clone()), false)
            .expect_err("release without bundle must fail");
        assert!(err.contains("Bundled ontology engine not found"));
        let _ = fs::remove_dir_all(&empty_dir);
    }
}
