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
 * 还原被 pairip 抽离到 "<主类>$c<数字>" 辅助类里的方法。拆成两个独立补丁：
 *
 *   restoreExtractedMethodsPatch  把抽离方法搬回主类（不删辅助类）
 *   removeExtractedClassesPatch   删除已被搬回的辅助类 + Method 占位类（dependsOn 上面那个）
 *
 * 组合：
 *   只开 "Restore extracted methods"  → 方法搬回主类，$c 辅助类保留
 *   开 "Remove extracted classes"     → 自动带上还原，搬回后再删 $c 类 + Method 占位类
 *
 * 识别：只看类名形如 <主类>$c<数字>（去掉 static / 首参==主类 的多段预判）。
 * 还原时仍要求在主类找到对应桩（方法体含 reflect Method.invoke）才动手——这是替换桩
 * 的必要前提，也顺带挡住了类名误判：没有反射桩的类不会被还原、也不会被删。
 *
 * Method 占位类：pairip 的桩通过 "sget-object <占位类>->字段:Ljava/lang/reflect/Method;"
 * 拿到反射 Method 再 invoke（如 ...stickers/hCD/RfMdgl）。这些占位类由 StartupLauncher.
 * restoreMethod() 填充，补丁一（只扫 restoreString）不碰，故在此收集并删除。
 */

private val logger = Logger.getLogger("RestoreExtracted")

private const val REFLECT_INVOKE = "Ljava/lang/reflect/Method;->invoke("
private const val METHOD_TYPE = "Ljava/lang/reflect/Method;"

// 已成功还原的抽离类，由还原补丁填充、供删除补丁读取（同进程共享）
internal val restoredExtractedTypes = LinkedHashSet<String>()

// 已还原的桩里引用的 Method 占位类（反射容器，如 RfMdgl），供删除补丁删除
internal val methodHolderTypes = LinkedHashSet<String>()

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

// ───────────────────────────────────────────────────────────────
// 补丁一：把抽离方法搬回主类（不删辅助类），并记录桩引用的 Method 占位类
// ───────────────────────────────────────────────────────────────
@Suppress("unused")
val restoreExtractedMethodsPatch = bytecodePatch(
    name = "Restore extracted methods",
    description = $$"Inlines methods hidden in $c<number> helper classes back into the host class.",
    default = false,
) {
    execute {
        restoredExtractedTypes.clear()
        methodHolderTypes.clear()

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
            restoredExtractedTypes += classDef.type
        }

        logger.info("restored ${restoredExtractedTypes.size} methods, " +
                "${methodHolderTypes.size} method-holder classes recorded")
    }
}

// ───────────────────────────────────────────────────────────────
// 补丁二：删除已被搬回的辅助类 + Method 占位类（依赖上面的还原补丁）
// ───────────────────────────────────────────────────────────────
@Suppress("unused")
val removeExtractedClassesPatch = bytecodePatch(
    name = "Remove extracted classes",
    description = $$"Removes the $c<number> helper classes and reflection method-holder classes left by pairip.",
    default = false,
) {
    dependsOn(restoreExtractedMethodsPatch)

    execute {
        forceFullBytecodeMode()
        val classMap = internalClassMap()

        // 1) 删已还原的 $c 抽离类
        var removed = 0
        restoredExtractedTypes.forEach { if (classMap.remove(it) != null) removed++ }

        // 2) 删 Method 占位类，但仅当全 app 已无 sget-object 残留引用它的字段
        //    （某个桩没被还原时它的 Method 字段还会被读，这种占位类保留以免悬空）
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

        logger.info("removed $removed helper classes + $holders method-holder classes")
    }
}