package app.patches.deobfuscate.sjshb57

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.logging.Logger

/*
 * 还原被抽离到 "<主类>$c<数字>" 辅助类里的方法。
 *
 * 抽离工具把原方法搬进一个 abstract 辅助类的 static 方法
 * （第一个参数充当 this），主类里只剩一个 Method.invoke 反射桩。
 *
 * 本 patch：
 *   1. 找到所有 "$c<数字>" 抽离类，取出它唯一的方法
 *   2. 把该方法体复制回主类的同名桩方法（连寄存器一起，布局天然对齐）
 *   3. 全部替换完后删除这些抽离类
 */

private val logger = Logger.getLogger("RestoreExtracted")

private const val REFLECT_INVOKE = "Ljava/lang/reflect/Method;->invoke("

/** 类型是否形如 "<主类>$c<数字>;" */
private fun isExtractedClass(type: String): Boolean {
    val idx = type.lastIndexOf("\$c")
    if (idx < 0) return false
    val digits = type.substring(idx + 2).removeSuffix(";")
    return digits.isNotEmpty() && digits.all { it.isDigit() }
}

/** "<主类>$c<数字>;" -> "<主类>;" */
private fun hostTypeOf(extractedType: String): String =
    extractedType.substringBeforeLast("\$c") + ";"

@Suppress("unused")
val restoreExtractedMethodsPatch = bytecodePatch(
    name = "Restore extracted methods",
    description = "Inlines methods hidden in helper classes back into the host class and removes the reflection stubs.",
    default = true,
) {
    compatibleWith(Compatibility(packageName = "com.twitter.android", name = "Twitter / X"))

    execute {
        // 1. 收集 抽离类型 -> 它唯一的实现方法
        val extracted = HashMap<String, Method>()
        classDefForEach { classDef ->
            if (!isExtractedClass(classDef.type)) return@classDefForEach
            classDef.methods.firstOrNull { it.implementation != null }
                ?.let { extracted[classDef.type] = it }
        }

        var restored = 0

        // 2. 逐个把抽离方法体搬回主类桩方法
        extracted.forEach typeLoop@{ (extractedType, extractedMethod) ->
            val hostClass = mutableClassDefByOrNull(hostTypeOf(extractedType)) ?: run {
                logger.warning("host not found for $extractedType")
                return@typeLoop
            }

            // 抽离方法参数[0] 是充当 this 的主类实例，去掉后即主类方法参数
            val extractedParams = extractedMethod.parameterTypes.map { it.toString() }
            if (extractedParams.isEmpty()) return@typeLoop
            val hostParams = extractedParams.drop(1)

            // 主类里找对应的反射桩：同名 + 参数对应 + 返回类型相同 + 含 Method.invoke
            val stub = hostClass.methods.firstOrNull { m ->
                m.name == extractedMethod.name &&
                    m.returnType == extractedMethod.returnType &&
                    m.parameterTypes.map { it.toString() } == hostParams &&
                    m.implementation?.instructions?.any { insn ->
                        insn.opcode == Opcode.INVOKE_VIRTUAL &&
                            (insn as? ReferenceInstruction)?.reference?.toString()
                                ?.contains(REFLECT_INVOKE) == true
                    } == true
            } ?: run {
                logger.warning("stub not found in ${hostTypeOf(extractedType)} for ${extractedMethod.name}")
                return@typeLoop
            }

            // 用主类桩的签名 + 抽离方法现成的实现，组成新方法替换旧桩
            // 寄存器布局两边一致（static 的首参 == 实例方法的 this），直接复用 implementation
            val restoredMethod = ImmutableMethod(
                hostClass.type,
                stub.name,
                stub.parameters,
                stub.returnType,
                stub.accessFlags,
                stub.annotations,
                stub.hiddenApiRestrictions,
                extractedMethod.implementation,
            ).toMutable()

            hostClass.methods.remove(stub)
            hostClass.methods.add(restoredMethod)
            restored++
        }

        // 3. 全部替换完，清空抽离类（转 MutableClass，Morphe 会从原 DEX 剥离，
        //    新 DEX 里只剩空壳。比 classMap.remove 可靠，不依赖 DEX 布局）
        var cleared = 0
        extracted.keys.forEach { type ->
            val mutableClass = mutableClassDefByOrNull(type) ?: return@forEach
            mutableClass.methods.clear()
            mutableClass.fields.clear()
            cleared++
        }

        logger.info("restored $restored methods, cleared $cleared helper classes")
    }
}
