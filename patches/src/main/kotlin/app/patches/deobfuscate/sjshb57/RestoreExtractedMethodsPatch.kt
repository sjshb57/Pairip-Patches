package app.patches.deobfuscate.sjshb57

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.logging.Logger

/*
 * 还原被抽离到 "<主类>$c<数字>" 辅助类里的方法。
 *
 * 抽离类特征（三重，缺一不可，避免误判 $c0 这类正常 Kotlin 内部类）：
 *   1. 类名形如 <主类>$c<数字>
 *   2. 含一个 static 方法
 *   3. 该 static 方法第一个参数 == 主类类型（充当 this）
 *
 * 还原后用 classMap.remove 真删除抽离类（需要 FULL 编译模式，见 forceFullBytecodeMode）。
 */

private val logger = Logger.getLogger("RestoreExtracted")

private const val REFLECT_INVOKE = "Ljava/lang/reflect/Method;->invoke("

/** 类名是否形如 "<主类>$c<数字>;"（仅初筛，真正判定还要看 static 方法签名） */
private fun nameLooksExtracted(type: String): Boolean {
    val idx = type.lastIndexOf("\$c")
    if (idx < 0) return false
    val digits = type.substring(idx + 2).removeSuffix(";")
    return digits.isNotEmpty() && digits.all { it.isDigit() }
}

/** "<主类>$c<数字>;" -> "<主类>;" */
private fun hostTypeOf(extractedType: String): String =
    extractedType.substringBeforeLast("\$c") + ";"

/** 反射拿到内部 classMap（用于真删除类） */
@Suppress("UNCHECKED_CAST")
private fun BytecodePatchContext.internalClassMap(): MutableMap<String, *> {
    val patchClasses = BytecodePatchContext::class.java
        .getDeclaredField("patchClasses")
        .apply { isAccessible = true }
        .get(this)
    return patchClasses.javaClass
        .getDeclaredField("classMap")
        .apply { isAccessible = true }
        .get(patchClasses) as MutableMap<String, *>
}

/** 反射把编译模式强制为 FULL，否则 STRIP 模式下 classMap.remove 删不掉原始类 */
private fun BytecodePatchContext.forceFullBytecodeMode() {
    val config = BytecodePatchContext::class.java
        .getDeclaredField("config")
        .apply { isAccessible = true }
        .get(this)
    config.javaClass
        .getDeclaredField("bytecodeMode")
        .apply { isAccessible = true }
        .set(config, BytecodeMode.FULL)
}

@Suppress("unused")
val restoreExtractedMethodsPatch = bytecodePatch(
    name = "Restore extracted methods",
    description = "Inlines methods hidden in helper classes back into the host class and removes the helper classes.",
    default = true,
) {
    compatibleWith(Compatibility(packageName = "com.twitter.android", name = "Twitter / X"))

    execute {
        // 1. 收集真正的抽离类（类名 + static 方法 + 第一参数 == 主类）
        val extracted = HashMap<String, Method>()
        classDefForEach { classDef ->
            if (!nameLooksExtracted(classDef.type)) return@classDefForEach
            val hostType = hostTypeOf(classDef.type)
            val method = classDef.methods.firstOrNull { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) &&
                    m.implementation != null &&
                    m.parameterTypes.firstOrNull()?.toString() == hostType
            } ?: return@classDefForEach
            extracted[classDef.type] = method
        }

        var restored = 0
        val restoredTypes = ArrayList<String>()

        // 2. 把抽离方法体搬回主类桩方法
        extracted.forEach typeLoop@{ (extractedType, extractedMethod) ->
            val hostClass = mutableClassDefByOrNull(hostTypeOf(extractedType)) ?: return@typeLoop

            // 抽离方法参数[0] 是 this，去掉后即主类方法参数
            val hostParams = extractedMethod.parameterTypes.map { it.toString() }.drop(1)

            val stub = hostClass.methods.firstOrNull { m ->
                m.name == extractedMethod.name &&
                    m.returnType == extractedMethod.returnType &&
                    m.parameterTypes.map { it.toString() } == hostParams &&
                    m.implementation?.instructions?.any { insn ->
                        (insn as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                            ?.reference?.toString()?.contains(REFLECT_INVOKE) == true
                    } == true
            } ?: return@typeLoop

            val restoredMethod = ImmutableMethod(
                hostClass.type,
                stub.name,
                stub.parameters,
                stub.returnType,
                stub.accessFlags,
                extractedMethod.annotations,
                stub.hiddenApiRestrictions,
                extractedMethod.implementation,
            ).toMutable()

            hostClass.methods.remove(stub)
            hostClass.methods.add(restoredMethod)
            restored++
            restoredTypes += extractedType
        }

        // 3. 真删除已成功还原的抽离类（未还原的保留，避免丢逻辑）
        forceFullBytecodeMode()
        val classMap = internalClassMap()
        var removed = 0
        restoredTypes.forEach { if (classMap.remove(it) != null) removed++ }

        logger.info("restored $restored methods, removed $removed helper classes")
    }
}
