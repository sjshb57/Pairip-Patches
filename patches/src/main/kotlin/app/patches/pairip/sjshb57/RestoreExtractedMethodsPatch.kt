package app.patches.pairip.sjshb57

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.logging.Logger

/*
 * 还原被 pairip 抽离到 "<主类>$c<数字>" 辅助类里的方法，并删除这些辅助类 + Method 占位类。
 *
 * 流程：
 *   1. 识别 <主类>$c<数字> 辅助类，把其中的 static 抽离方法搬回主类（替换主类里的反射桩）
 *   2. 替换桩前记录桩引用的 Method 占位类（反射容器，如 RfMdgl）
 *   3. 强制 FULL，删除已还原的 $c 辅助类
 *   4. 删除 Method 占位类（仅当全 app 已无 sget-object 残留引用它，避免悬空）
 *
 * 识别：只看类名形如 <主类>$c<数字>。还原时要求主类有对应桩（方法体含 reflect Method.invoke）
 * 才动手——既是替换桩的必要前提，也挡住类名误判：没有反射桩的类不会被还原、也不会被删。
 *
 * Method 占位类：pairip 的桩通过 "sget-object <占位类>->字段:Ljava/lang/reflect/Method;"
 * 拿到反射 Method 再 invoke（如 ...stickers/hCD/RfMdgl）。这些占位类由 StartupLauncher.
 * restoreMethod() 填充，补丁一（只扫 restoreString）不碰，故在此收集并删除。
 */

private val logger = Logger.getLogger("RestoreExtracted")

private const val REFLECT_INVOKE = "Ljava/lang/reflect/Method;->invoke("
private const val METHOD_TYPE = "Ljava/lang/reflect/Method;"

/**
 * 补丁二还原过的“主类” type 集合，供补丁四（内联 call wrapper）限定范围使用：
 * 只有 pairip 真正动过（有 $c 抽离方法被还原）的主类才允许内联，正常类一律不碰。
 */
internal val restoredHostTypes = mutableSetOf<String>()

/** 类名是否形如 "<主类>$c<数字>;" */
private fun nameLooksExtracted(type: String): Boolean {
    val idx = type.lastIndexOf($$"$c")
    if (idx < 0) return false
    val digits = type.substring(idx + 2).removeSuffix(";")
    return digits.isNotEmpty() && digits.all { it.isDigit() }
}

/** "<主类>$c<数字>;" → "<主类>;" */
private fun hostTypeOf(extractedType: String): String =
    extractedType.substringBeforeLast($$"$c") + ";"

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
    description = $$"Inlines methods hidden in $c<number> helper classes back into the host class, then removes those helper and reflection method-holder classes.",
    default = true,
) {
    execute {
        restoredHostTypes.clear()
        val restoredTypes = LinkedHashSet<String>()
        val methodHolderTypes = LinkedHashSet<String>()

        // ── 1) 还原：把 $c 辅助类的 static 抽离方法搬回主类
        classDefForEach { classDef ->
            // 识别：只看类名 <主类>$c<数字>
            if (!nameLooksExtracted(classDef.type)) return@classDefForEach
            val hostType = hostTypeOf(classDef.type)

            // 抽离方法 = 辅助类里的 static 方法（带实现）
            val extractedMethod = classDef.methods.firstOrNull { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) && m.implementation != null
            } ?: return@classDefForEach

            val hostClass = mutableClassDefByOrNull(hostType) ?: return@classDefForEach

            // 抽离方法参数[0] 是 this，去掉后即主类方法的参数列表
            val hostParams = extractedMethod.parameterTypes.map { it.toString() }.drop(1)

            // 在主类找桩：同名、同返回、参数匹配，且方法体含 reflect Method.invoke
            val stub = hostClass.methods.firstOrNull { m ->
                m.name == extractedMethod.name &&
                        m.returnType == extractedMethod.returnType &&
                        m.parameterTypes.map { it.toString() } == hostParams &&
                        m.implementation?.instructions?.any { insn ->
                            (insn as? ReferenceInstruction)
                                ?.reference?.toString()?.contains(REFLECT_INVOKE) == true
                        } == true
            } ?: return@classDefForEach

            // 替换桩之前，先记录桩里 sget-object 的 Method 占位类（反射容器）
            stub.implementation?.instructions?.forEach { insn ->
                if (insn.opcode == Opcode.SGET_OBJECT) {
                    val ref = (insn as? ReferenceInstruction)?.reference
                    if (ref is FieldReference && ref.type == METHOD_TYPE)
                        methodHolderTypes += ref.definingClass
                }
            }

            // 用抽离方法的实现 + 注解（保留 @Nullable 等）替换桩
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
            restoredTypes += classDef.type
            restoredHostTypes += hostType
        }

        // ── 2) 删除：强制 FULL，删除已还原的 $c 辅助类
        forceFullBytecodeMode()
        val classMap = internalClassMap()
        var removed = 0
        restoredTypes.forEach { if (classMap.remove(it) != null) removed++ }

        // ── 3) 删 Method 占位类：仅当全 app 已无 sget-object 残留引用它的字段
        //      （某个桩没被还原时它的 Method 字段还会被读，这种占位类保留以免悬空）
        val stillReferenced = HashSet<String>()
        classDefForEach { cd ->
            cd.methods.forEach { m ->
                m.implementation?.instructions?.forEach { insn ->
                    if (insn.opcode == Opcode.SGET_OBJECT) {
                        val ref = (insn as? ReferenceInstruction)?.reference
                        if (ref is FieldReference && ref.definingClass in methodHolderTypes)
                            stillReferenced += ref.definingClass
                    }
                }
            }
        }
        var holders = 0
        methodHolderTypes.forEach { type ->
            if (type !in stillReferenced && classMap.remove(type) != null) holders++
        }

        logger.info("restored ${restoredTypes.size} methods, removed $removed helper + $holders method-holder classes")
    }
}
