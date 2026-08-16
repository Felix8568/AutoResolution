@file:Suppress("PrivateApi", "BlockedPrivateApi")

package top.loongway.autoresolution.shizuku.proxy

import android.os.Build
import android.os.IBinder
import java.lang.reflect.Method

/**
 * Android 14+ display token 获取器。
 * Google 在 14 中删除了 [SurfaceControlProxy.getInternalDisplayToken]，
 * 必须用自定义 ClassLoader 从 /system/framework/services.jar 加载
 * [com.android.server.display.DisplayControl] 来获取 display token。
 * 在 Shizuku UserService 进程中运行，不受 hidden API 限制。
 */
object DisplayControlProxy {

    private val CLASS: Class<*> by lazy {
        val classLoaderFactory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val createClassLoader = classLoaderFactory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java
        )
        val classLoader = createClassLoader.invoke(
            null, "/system/framework/services.jar", null, null,
            ClassLoader.getSystemClassLoader(), 0, true, null
        ) as ClassLoader

        classLoader.loadClass("com.android.server.display.DisplayControl").also { cls ->
            val loadLibrary = Runtime::class.java.getDeclaredMethod(
                "loadLibrary0", Class::class.java, String::class.java
            )
            loadLibrary.isAccessible = true
            loadLibrary.invoke(Runtime.getRuntime(), cls, "android_servers")
        }
    }

    private val getPhysicalDisplayIds: Method by lazy {
        CLASS.getMethod("getPhysicalDisplayIds")
    }

    private val getPhysicalDisplayToken: Method by lazy {
        CLASS.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
    }

    fun getPhysicalDisplayIds(): LongArray {
        return getPhysicalDisplayIds.invoke(null) as LongArray
    }

    fun getPhysicalDisplayToken(physicalDisplayId: Long): IBinder {
        return getPhysicalDisplayToken.invoke(null, physicalDisplayId) as IBinder
    }

    fun getToken(): IBinder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val ids = getPhysicalDisplayIds()
            return getPhysicalDisplayToken(ids[0])
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return SurfaceControlProxy.getInternalDisplayToken()
        }
        return SurfaceControlProxy.getBuiltInDisplay()
    }
}
