package app.patches.pairip.sjshb57

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.xml.XmlElement
import java.util.logging.Logger

/*
 * Fix pairip manifest —— Remove pairip protection 的配套 resource patch（无名，作为依赖自动启用）
 *
 * 问题：
 *   pairip 会把 <application android:name> 改成自己的壳 com.pairip.application.Application，
 *   该壳类继承自应用原本的 Application（例如 ws.stash.app.StashApplication）。
 *   Remove pairip protection 会真删除所有 Lcom/pairip/ 类，但 AndroidManifest.xml 里仍然引用
 *   com.pairip.application.Application → 启动时 ClassNotFoundException，安装后立即闪退。
 *   同理，残留的 com.pairip.licensecheck.LicenseActivity 等组件也指向已被删除的类。
 *
 * 修复：
 *   1. bytecode 阶段（Remove pairip protection 删除类之前）记录壳 Application 的父类
 *      → resolveOriginalApplication()
 *   2. 本 patch 的 finalize 阶段（所有 execute 之后）改写 manifest：
 *      · application android:name = 原 Application；若原父类就是 android.app.Application，则删除该属性
 *      · 删除所有指向 com.pairip.* 的四大组件 / activity-alias
 *      · 其它 <application> 属性（如 appComponentFactory）若指向 com.pairip.* 也一并删除
 */

private val logger = Logger.getLogger("FixPairipManifest")

private const val PAIRIP_JAVA_PREFIX = "com.pairip."
private const val ANDROID_APPLICATION = "Landroid/app/Application;"

private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

/**
 * 原始 Application 的解析结果：
 *   null                 → 未解析（未运行 / 不需要改）
 *   ANDROID_APPLICATION  → 原应用没有自定义 Application，应删除 android:name
 *   其它                 → 原 Application 的 dex 类型（Lxx/yy/Zz;）
 */
@Volatile
private var originalApplicationType: String? = null

private fun dexTypeToJava(type: String) = type.removePrefix("L").removeSuffix(";").replace('/', '.')

/** 把 manifest 里可能为相对写法（.Foo / Foo）的类名补全 */
private fun resolveClassName(name: String, packageName: String): String = when {
    name.startsWith(".") -> packageName + name
    !name.contains('.') -> "$packageName.$name"
    else -> name
}

/**
 * 在 bytecode 阶段、pairip 类被删除前调用：确定原本的 Application。
 *
 *   A. 壳类 Lcom/pairip/application/Application; 还在 → 直接取其 superclass（最准确）
 *   B. 壳类已不存在（例如 APK 已被旧版本补丁处理过、manifest 却没修）→ 兜底：
 *      在非 pairip 类中找 android.app.Application 的子类，取继承链的"叶子"；
 *      只有唯一一个叶子时才采用，否则放弃（交给 finalize 报错，避免瞎猜）
 */
internal fun BytecodePatchContext.resolveOriginalApplication(shellType: String) {
    originalApplicationType = null

    classDefByOrNull(shellType)?.let { shell ->
        val superType = shell.superclass ?: ANDROID_APPLICATION
        originalApplicationType = superType
        logger.info("pairip: shell application super = $superType")
        return
    }

    // 兜底 B
    val superOf = HashMap<String, String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lcom/pairip/")) return@classDefForEach
        classDef.superclass?.let { superOf[classDef.type] = it }
    }

    fun isApplicationSubclass(type: String): Boolean {
        var current: String? = superOf[type]
        var depth = 0
        while (current != null && depth++ < 32) {
            if (current == ANDROID_APPLICATION) return true
            current = superOf[current]
        }
        return false
    }

    val appClasses = superOf.keys.filter(::isApplicationSubclass).toSet()
    val parents = appClasses.mapNotNullTo(HashSet()) { superOf[it] }
    val leaves = appClasses.filter { it !in parents }

    originalApplicationType = when (leaves.size) {
        0 -> ANDROID_APPLICATION
        1 -> leaves.single()
        else -> null.also {
            logger.warning("pairip: shell application missing and multiple candidates found: $leaves")
        }
    }
    logger.info("pairip: shell application missing, fallback resolved = $originalApplicationType")
}

internal val fixPairipManifestPatch = resourcePatch(
    description = "Points <application android:name> back to the original Application and removes " +
        "manifest entries that reference deleted com.pairip classes.",
) {
    execute {
        // 真正的工作在 finalize 里做（需要 bytecode patch 先收集到原 Application）
    }

    finalize {
        resourceXml("AndroidManifest.xml").use { xml ->
            val root = xml.root
            val packageName = root.attribute("package") ?: ""
            val application = root.children("application").firstOrNull() ?: run {
                originalApplicationType = null
                return@use
            }

            fun XmlElement.isPairipAttr(attr: String): Boolean {
                val v = attribute(attr) ?: return false
                return v.isNotEmpty() && resolveClassName(v, packageName).startsWith(PAIRIP_JAVA_PREFIX)
            }

            // 1. <application android:name>
            if (application.isPairipAttr("android:name")) {
                val original = originalApplicationType ?: throw PatchException(
                    "AndroidManifest.xml uses pairip application " +
                        "\"${application.attribute("android:name")}\" but the original Application " +
                        "class could not be determined.",
                )
                if (original == ANDROID_APPLICATION || original.startsWith("Lcom/pairip/")) {
                    application.removeAttribute("android:name")
                    logger.info("pairip: manifest application name removed (default android.app.Application)")
                } else {
                    val javaName = dexTypeToJava(original)
                    application.setAttribute("android:name", javaName)
                    logger.info("pairip: manifest application name -> $javaName")
                }
            }

            // 2. 其它 <application> 属性里对 pairip 类的引用（如 appComponentFactory / backupAgent）
            listOf("android:appComponentFactory", "android:backupAgent").forEach { attr ->
                if (application.isPairipAttr(attr)) {
                    logger.info("pairip: manifest removed $attr=${application.attribute(attr)}")
                    application.removeAttribute(attr)
                }
            }

            // 3. 指向 com.pairip.* 的组件（如 com.pairip.licensecheck.LicenseActivity）
            COMPONENT_TAGS.forEach { tag ->
                xml.elements(tag).toList()
                    .filter { it.isPairipAttr("android:name") || it.isPairipAttr("android:targetActivity") }
                    .forEach { element ->
                        logger.info("pairip: manifest removed <$tag ${element.attribute("android:name")}>")
                        element.remove()
                    }
            }
        }

        originalApplicationType = null
    }
}
