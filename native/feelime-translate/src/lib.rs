//! Feelime 内置离线翻译（中文 <-> 英语）。
//!
//! 引擎是 slimt（bergamot 模型的 C++ 推理实现），模型文件是 Mozilla 的
//! zhen / enzh 两个方向（intgemm 8 位量化，各约 16–20 MB）。本 crate 只做
//! 最薄的一层：加载两个模型、按方向翻译一段文本。JNI 面对应
//! com.feelime.ime.OfflineTranslator。
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use slimt_sys::{BlockingService, ModelArch, TranslationModel};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::ptr;
use std::sync::Mutex;

static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

fn set_error(message: impl Into<String>) {
    if let Ok(mut guard) = LAST_ERROR.lock() {
        *guard = message.into();
    }
}

struct Engine {
    service: BlockingService,
    zh_en: TranslationModel,
    en_zh: TranslationModel,
}

/// 单词表（zhen）与双词表（enzh：srcvocab + trgvocab）两种布局都认。
fn load_pair(dir: &Path, pair: &str) -> Result<TranslationModel, String> {
    let model = dir.join(format!("model.{pair}.intgemm.alphas.bin"));
    let lex = dir.join(format!("lex.50.50.{pair}.s2t.bin"));
    let shared = dir.join(format!("vocab.{pair}.spm"));
    let src = dir.join(format!("srcvocab.{pair}.spm"));
    let trg = dir.join(format!("trgvocab.{pair}.spm"));
    let arch = ModelArch::default();
    let loaded = if shared.exists() {
        TranslationModel::with_arch(&model, &shared, &lex, None, arch)
    } else {
        TranslationModel::with_arch_and_target_vocab(&model, &src, &lex, None, arch, Some(&trg))
    };
    loaded.map_err(|e| format!("load {pair}: {e}"))
}

fn load_engine(dir: &Path) -> Result<Engine, String> {
    let zh_en = load_pair(dir, "zhen")?;
    let en_zh = load_pair(dir, "enzh")?;
    Ok(Engine {
        service: BlockingService::with_workers(2, 0),
        zh_en,
        en_zh,
    })
}

/// 加载模型目录，成功返回引擎句柄（进程生命周期内常驻），失败返回 0，
/// 原因见 nativeLastError。
#[no_mangle]
pub extern "system" fn Java_com_feelime_ime_OfflineTranslator_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) -> jlong {
    let dir: String = match env.get_string(&dir) {
        Ok(value) => value.into(),
        Err(e) => {
            set_error(format!("dir: {e}"));
            return 0;
        }
    };
    match catch_unwind(AssertUnwindSafe(|| load_engine(Path::new(&dir)))) {
        Ok(Ok(engine)) => Box::into_raw(Box::new(engine)) as jlong,
        Ok(Err(e)) => {
            set_error(e);
            0
        }
        Err(_) => {
            set_error("panic while loading models");
            0
        }
    }
}

/// pair：0 = 中译英（zhen），1 = 英译中（enzh）。出错返回 null。
#[no_mangle]
pub extern "system" fn Java_com_feelime_ime_OfflineTranslator_nativeTranslate(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    pair: jint,
    text: JString,
) -> jstring {
    if handle == 0 {
        set_error("engine not loaded");
        return ptr::null_mut();
    }
    let text: String = match env.get_string(&text) {
        Ok(value) => value.into(),
        Err(e) => {
            set_error(format!("text: {e}"));
            return ptr::null_mut();
        }
    };
    let engine = unsafe { &*(handle as *const Engine) };
    let translated = catch_unwind(AssertUnwindSafe(|| {
        let model = if pair == 0 { &engine.zh_en } else { &engine.en_zh };
        engine.service.translate(model, &[text.as_str()]).into_iter().next()
    }));
    match translated {
        Ok(Some(out)) => match env.new_string(out) {
            Ok(java) => java.into_raw(),
            Err(e) => {
                set_error(format!("result: {e}"));
                ptr::null_mut()
            }
        },
        Ok(None) => {
            set_error("empty result");
            ptr::null_mut()
        }
        Err(_) => {
            set_error("panic while translating");
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_feelime_ime_OfflineTranslator_nativeLastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let message = LAST_ERROR.lock().map(|g| g.clone()).unwrap_or_default();
    match env.new_string(message) {
        Ok(java) => java.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}
