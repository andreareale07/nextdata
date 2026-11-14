//! Thin bridge: JNI exports (Android + desktop JVM) and a plain C ABI. No logic here.
use crate::engine::{Engine, Sink};
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jlong, jstring};
use jni::{JNIEnv, JavaVM};
use std::ffi::{c_char, CStr, CString};
use std::sync::{Arc, Mutex, OnceLock};

static ENGINE: OnceLock<Arc<Engine>> = OnceLock::new();
static VM: OnceLock<JavaVM> = OnceLock::new();
static LISTENER: Mutex<Option<GlobalRef>> = Mutex::new(None);
static C_CB: Mutex<Option<extern "C" fn(*const c_char)>> = Mutex::new(None);

fn sink() -> Sink {
    Arc::new(|json: String| {
        // JVM listener
        let l = LISTENER.lock().unwrap().clone();
        if let (Some(l), Some(vm)) = (l, VM.get()) {
            if let Ok(mut env) = vm.attach_current_thread_permanently() {
                if let Ok(s) = env.new_string(&json) {
                    let _ = env.call_method(l.as_obj(), "onEvent", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
                }
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear(); // a throwing listener must never kill the engine
                }
            }
        }
        // C listener
        let cb = *C_CB.lock().unwrap();
        if let Some(cb) = cb {
            if let Ok(c) = CString::new(json) {
                cb(c.as_ptr());
            }
        }
    })
}

fn engine() -> &'static Arc<Engine> {
    ENGINE.get_or_init(|| Engine::new(sink()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_nextdata_bridge_NativeBridge_submit(mut env: JNIEnv, _c: JClass, cmd: JString) -> jlong {
    if VM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = VM.set(vm);
        }
    }
    let s: String = match env.get_string(&cmd) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let r = std::panic::catch_unwind(|| engine().submit(&s));
    r.unwrap_or(0) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_nextdata_bridge_NativeBridge_setListener(env: JNIEnv, _c: JClass, listener: JObject) {
    if VM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = VM.set(vm);
        }
    }
    let g = if listener.is_null() { None } else { env.new_global_ref(listener).ok() };
    *LISTENER.lock().unwrap() = g;
    let _ = engine();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_nextdata_bridge_NativeBridge_cancel(_env: JNIEnv, _c: JClass, op: jlong) {
    let _ = std::panic::catch_unwind(|| engine().cancel(op as u64));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_nextdata_bridge_NativeBridge_version(env: JNIEnv, _c: JClass) -> jstring {
    env.new_string(format!("nd_engine {}", env!("CARGO_PKG_VERSION"))).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

// ------------------------------------------------------------------ C ABI (3 functions + version)

#[unsafe(no_mangle)]
pub extern "C" fn nd_set_event_callback(cb: Option<extern "C" fn(*const c_char)>) {
    *C_CB.lock().unwrap() = cb;
    let _ = engine();
}

/// Returns the op id (0 on malformed envelope).
#[unsafe(no_mangle)]
pub extern "C" fn nd_submit(json: *const c_char) -> u64 {
    if json.is_null() {
        return 0;
    }
    let s = unsafe { CStr::from_ptr(json) }.to_string_lossy().to_string();
    std::panic::catch_unwind(|| engine().submit(&s)).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn nd_cancel(op: u64) {
    let _ = std::panic::catch_unwind(|| engine().cancel(op));
}
