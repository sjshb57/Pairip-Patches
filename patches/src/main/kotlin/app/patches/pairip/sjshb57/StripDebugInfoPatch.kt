package app.patches.pairip.sjshb57

import app.morphe.patcher.patch.bytecodePatch
import java.util.logging.Logger

/*
 * 清除所有类的调试信息：.source（源文件名）和 .line（行号等 debug items）。
 * 可选补丁，默认不启用（default = false）。
 *
 * 清除方式（均为就地修改，不重建指令，不会破坏跳转/switch/try）：
 *   .source → MutableClass.setSourceFile(null)
 *   .line   → 遍历方法 implementation 的每条指令，clear 其所在 MethodLocation 的 debugItems
 *             （debugItems 含行号/局部变量等纯调试数据，对运行无影响）
 *
 * 注意：清 debug 要遍历并修改几乎所有类，会让打补丁变慢，且使编译写出所有类
 * （STRIP 会退化成 FULL 的写出量）。与删类补丁（已 FULL）一起用时不增加额外负担。
 */

private val logger = Logger.getLogger("StripDebugInfo")

@Suppress("unused")
val stripDebugInfoPatch = bytecodePatch(
    name = "Strip debug info",
    description = "Removes .line (line numbers) and .source (source file name) debug data from every class. Optional, disabled by default.",
    default = false,
) {
    execute {
        var sourceCleared = 0
        var lineCleared = 0

        classDefForEach { classDef ->
            val hasSource = classDef.sourceFile != null
            // 有 source 必须 mutable；无 source 时再判断有没有 line（决定值不值得 mutable）
            val needMutable = hasSource || classDef.methods.any { m ->
                m.implementation?.debugItems?.any() == true
            }
            if (!needMutable) return@classDefForEach

            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach

            // .source
            if (hasSource) {
                mutableClass.setSourceFile(null)
                sourceCleared++
            }

            // .line：就地清每条指令所在位置的 debugItems，边清边记（无需预先知道 hasLine）
            mutableClass.methods.forEach { method ->
                val impl = method.implementation ?: return@forEach
                var cleared = false
                impl.instructions.forEach { insn ->
                    val items = insn.location.debugItems
                    if (items.isNotEmpty()) {
                        items.clear()
                        cleared = true
                    }
                }
                if (cleared) lineCleared++
            }
        }

        logger.info("stripped .source from $sourceCleared classes, .line from $lineCleared methods")
    }
}
