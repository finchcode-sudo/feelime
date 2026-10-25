package com.feelime.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线翻译客户端：绑定 Offline Translator（dev.davidv.translator）暴露的
 * AIDL 服务做翻译。引擎与语言包都在对方 App 里，Feelime 只是调用方——
 * 文本经本机进程间调用往返，不走网络。
 *
 * 线程：所有状态只在主线程读写（bind 回调本来就在主线程；外部调用先 post）。
 * 回调的 [onResult] 恰好触发一次，运行在主线程。
 */
class TranslatorClient(context: Context) {
    sealed class Result {
        data class Ok(val text: String) : Result()

        /** [kind]：not_installed / not_ready / no_detect / unavailable /
         *  timeout / error。[language] 仅 unavailable 带（对方的显示名）。 */
        data class Fail(val kind: String, val language: String? = null, val message: String? = null) : Result()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: ITranslationService? = null
    private var bound = false
    private var binding = false
    private val waiting = ArrayList<() -> Unit>()
    private val idleUnbind = Runnable { unbindNow() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null) ITranslationService.Stub.asInterface(binder) else null
            val queued = ArrayList(waiting)
            waiting.clear()
            queued.forEach { it() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            unbindNow()
        }

        override fun onNullBinding(name: ComponentName?) {
            // 对方进程起来了但没给 binder：按未就绪处理。
            service = null
            binding = false
            val queued = ArrayList(waiting)
            waiting.clear()
            queued.forEach { it() }
        }
    }

    /** 翻译 App 是否已安装（需要 manifest 的 queries 声明才可见）。 */
    fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * 翻译 [text]。[from] 为空串 = 让对方自动检测；[to] 为对方的语言代码
     * （zh/en/ja…，见对方目录）。任何线程可调。
     */
    fun translate(text: String, from: String, to: String, onResult: (Result) -> Unit) {
        main.post {
            if (!isInstalled()) {
                onResult(Result.Fail("not_installed"))
                return@post
            }
            main.removeCallbacks(idleUnbind)
            val done = AtomicBoolean(false)
            val finish: (Result) -> Unit = { result ->
                if (done.compareAndSet(false, true)) main.post { onResult(result) }
            }
            main.postDelayed({ finish(Result.Fail("timeout")) }, TIMEOUT_MS)
            withService { svc ->
                if (svc == null) {
                    finish(Result.Fail("not_ready"))
                    return@withService
                }
                try {
                    svc.translate(text, from, to, object : ITranslationCallback.Stub() {
                        override fun onTranslationResult(translatedText: String?) {
                            finish(Result.Ok(translatedText.orEmpty()))
                        }

                        override fun onTranslationError(error: TranslationError?) {
                            finish(mapError(error))
                        }
                    })
                } catch (e: Exception) {
                    // RemoteException（对方进程死亡）等：丢弃连接，下次重绑。
                    service = null
                    finish(Result.Fail("error", message = e.javaClass.simpleName))
                }
            }
            // 空闲一会儿就解绑，不长期占着对方进程。
            main.postDelayed(idleUnbind, IDLE_UNBIND_MS)
        }
    }

    /** 释放连接（服务销毁时调）。 */
    fun shutdown() {
        main.post {
            main.removeCallbacksAndMessages(null)
            unbindNow()
        }
    }

    private fun withService(block: (ITranslationService?) -> Unit) {
        val current = service
        if (current != null) {
            block(current)
            return
        }
        waiting.add { block(service) }
        if (binding) return
        binding = true
        val intent = Intent(ACTION).setPackage(PACKAGE)
        val ok = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (ok) {
            bound = true
        } else {
            binding = false
            val queued = ArrayList(waiting)
            waiting.clear()
            queued.forEach { it() }
        }
    }

    private fun unbindNow() {
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
        binding = false
        service = null
    }

    private fun mapError(error: TranslationError?): Result.Fail {
        if (error == null) return Result.Fail("error")
        return when (error.type) {
            ErrorType.COULD_NOT_DETECT_LANGUAGE -> Result.Fail("no_detect")
            ErrorType.DETECTED_BUT_UNAVAILABLE -> Result.Fail("unavailable", language = error.language)
            else -> Result.Fail("error", message = error.message)
        }
    }

    companion object {
        const val PACKAGE = "dev.davidv.translator"
        const val ACTION = "dev.davidv.translator.ITranslationService"
        private const val TIMEOUT_MS = 30_000L
        private const val IDLE_UNBIND_MS = 60_000L
    }
}
