#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader};
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

fn spawn_engine() -> Engine {
    let jar = std::env::var("ONTOSTUDIO_ENGINE_JAR").unwrap_or_else(|_| {
        format!(
            "{}/../../../services/engine/target/engine.jar",
            env!("CARGO_MANIFEST_DIR")
        )
    });
    let mut child = Command::new("java")
        .arg("-jar")
        .arg(&jar)
        .arg("--port")
        .arg("0")
        .arg("--watch-parent")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .unwrap_or_else(|error| panic!("failed to start engine at {jar}: {error}"));
    let stdout = child.stdout.take().expect("engine stdout");
    let mut line = String::new();
    BufReader::new(stdout)
        .read_line(&mut line)
        .expect("engine handshake");
    let handshake: serde_json::Value =
        serde_json::from_str(line.trim()).expect("engine handshake json");
    Engine {
        info: EngineInfo {
            port: handshake["port"].as_u64().expect("engine port") as u16,
            token: handshake["token"]
                .as_str()
                .expect("engine token")
                .to_string(),
        },
        child: Mutex::new(child),
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            app.manage(spawn_engine());
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
