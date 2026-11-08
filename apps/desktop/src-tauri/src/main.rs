#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;

use tauri::{Manager, State};

#[derive(Clone, serde::Serialize)]
struct EngineInfo {
    port: u16,
    token: String,
}

struct Engine {
    info: EngineInfo,
    child: Mutex<Child>,
}

#[tauri::command]
fn engine_info(state: State<Engine>) -> EngineInfo {
    state.info.clone()
}

fn parse_handshake(line: &str) -> Result<EngineInfo, String> {
    let value: serde_json::Value =
        serde_json::from_str(line.trim()).map_err(|error| error.to_string())?;
    Ok(EngineInfo {
        port: value["port"]
            .as_u64()
            .ok_or("handshake is missing a port")? as u16,
        token: value["token"]
            .as_str()
            .ok_or("handshake is missing a token")?
            .to_string(),
    })
}

/// Prefers the bundled runtime and jar, then falls back to a development build.
fn resolve_engine(app: &tauri::AppHandle) -> (PathBuf, PathBuf) {
    if let Ok(jar) = std::env::var("ONTOSTUDIO_ENGINE_JAR") {
        return (PathBuf::from("java"), PathBuf::from(jar));
    }
    if let Ok(resource_dir) = app.path().resource_dir() {
        let bundled_jar = resource_dir.join("resources/engine.jar");
        let bundled_java = resource_dir.join("resources/jre/bin/java");
        if bundled_jar.exists() && bundled_java.exists() {
            return (bundled_java, bundled_jar);
        }
    }
    let dev_jar = PathBuf::from(format!(
        "{}/../../../services/engine/target/engine.jar",
        env!("CARGO_MANIFEST_DIR")
    ));
    (PathBuf::from("java"), dev_jar)
}

fn spawn_engine(app: &tauri::AppHandle) -> Engine {
    let (java, jar) = resolve_engine(app);
    let mut child = Command::new(&java)
        .arg("-jar")
        .arg(&jar)
        .arg("--port")
        .arg("0")
        .arg("--watch-parent")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .unwrap_or_else(|error| panic!("failed to start engine at {jar:?}: {error}"));
    let stdout = child.stdout.take().expect("engine stdout");
    let mut line = String::new();
    BufReader::new(stdout)
        .read_line(&mut line)
        .expect("engine handshake");
    Engine {
        info: parse_handshake(&line).expect("engine handshake"),
        child: Mutex::new(child),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            app.manage(spawn_engine(app.handle()));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![engine_info])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app_handle, event| {
            if let tauri::RunEvent::Exit = event {
                if let Some(engine) = app_handle.try_state::<Engine>() {
                    if let Ok(mut child) = engine.child.lock() {
                        let _ = child.kill();
                    }
                }
            }
        });
}
