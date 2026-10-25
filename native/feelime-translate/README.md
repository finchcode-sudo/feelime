# feelime-translate

键盘内置的离线中英互译引擎（slimt 推理 + Mozilla bergamot 模型），只暴露 JNI：
`com.feelime.ime.OfflineTranslator.{nativeLoad,nativeTranslate,nativeLastError}`。

构建在 CI 里完成（`.github/workflows/main.yml`）：cargo-ndk 编出
`libfeelime_translate.so` 放进 `app/src/main/jniLibs/arm64-v8a/`。

模型文件（7 个，约 75 MB）来自 Release `translate-models-v1` 的 `models-zh-en.zip`
（内含 `bin/`：`model/vocab/lex.*zhen|enzh*`），CI 下载后放进
`app/src/main/assets/translate-models/`，首次翻译时复制到应用私有目录。
