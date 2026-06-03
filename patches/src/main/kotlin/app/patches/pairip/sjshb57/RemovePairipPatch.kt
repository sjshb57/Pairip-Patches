package app.patches.pairip.sjshb57

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

/*
 * pairip 字符串还原 + VMRunner 清空 + 占位类/pairip 类清理（Morphe，纯字节码）
 * 逻辑严格对照 string_restorer.py。
 */

private val logger = Logger.getLogger("RemovePairip")

private const val PAIRIP_PREFIX = "Lcom/pairip/"
private const val APPLICATION_CLASS = "Lcom/pairip/application/Application;"
private const val STARTUP_LAUNCHER_CLASS = "Lcom/pairip/StartupLauncher;"
// StartupLauncher 里只有 restoreString() 是纯字符串来源（同 Application 格式）；
// restoreMethod() 是方法占位，绝不在这里碰，避免误删 Method 占位类。
private const val RESTORE_STRING_METHOD = "restoreString"

// 字符串映射的两个来源：Application 类 + StartupLauncher.restoreString()
private val STRING_SOURCE_CLASSES = setOf(APPLICATION_CLASS, STARTUP_LAUNCHER_CLASS)
private const val VMRUNNER_CLASS = "Lcom/pairip/VMRunner;"
private const val APPKILLER_METHOD = "appkiller"
private const val OBJECTLOGGER_SIG = "/ObjectLogger;->logstring("

private fun String.toSmaliLiteral(): String = buildString {
    for (c in this@toSmaliLiteral) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
}

private fun minimalReturnFor(returnType: String): String = when (returnType) {
    "V" -> "return-void"
    "Z", "B", "C", "S", "I", "F" -> "const/4 v0, 0x0\nreturn v0"
    "J", "D" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
    else -> "const/4 v0, 0x0\nreturn-object v0"
}

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

/** 方法体是否只有一条 return-void（被清空的空方法） */
private fun com.android.tools.smali.dexlib2.iface.Method.isOnlyReturnVoid(): Boolean {
    val insns = implementation?.instructions?.toList() ?: return false
    return insns.size == 1 && insns[0].opcode == Opcode.RETURN_VOID
}

/** 指令是否是对 Lcom/pairip/ 的 invoke 调用或字段访问（对应 py 阶段6 的步骤2、3） */
private fun isPairipRef(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Boolean {
    val ref = (insn as? ReferenceInstruction)?.reference ?: return false
    return when (ref) {
        is MethodReference -> ref.definingClass.startsWith(PAIRIP_PREFIX)
        is FieldReference -> ref.definingClass.startsWith(PAIRIP_PREFIX)
        else -> false
    }
}

@Suppress("unused")
val removePairipPatch = bytecodePatch(
    name = "Remove pairip protection",
    description = "Restores obfuscated strings and removes pairip bytecode protection.",
    default = false,
) {
    execute {
        val stringMap = HashMap<String, String>()
        val placeholderClasses = HashSet<String>()

        // ── Step 1A: 字符串来源类（Application + StartupLauncher.restoreString()）
        //    - 所有 sput-object 的目标类 → 占位类
        //    - const-string 紧跟 sput-object → 字符串映射
        //    （restoreMethod() 里 const-string 后面是 invoke-static 不相邻，自动排除）
        classDefForEach { classDef ->
            if (classDef.type !in STRING_SOURCE_CLASSES) return@classDefForEach
            classDef.methods.forEach methods@{ method ->
                // StartupLauncher 只看 restoreString()（纯字符串来源，同 Application 格式）；
                // restoreMethod() 是方法占位，跳过，绝不在此收集它的 Method 占位类。
                if (classDef.type == STARTUP_LAUNCHER_CLASS && method.name != RESTORE_STRING_METHOD)
                    return@methods
                val insns = method.instructionsOrNull?.toList() ?: return@methods
                insns.forEachIndexed { i, insn ->
                    if (insn.opcode == Opcode.SPUT_OBJECT) {
                        val ref = (insn as ReferenceInstruction).reference
                        if (ref is FieldReference) placeholderClasses += ref.definingClass
                    }
                    if (insn.opcode == Opcode.CONST_STRING && i + 1 < insns.size) {
                        val next = insns[i + 1]
                        if (next.opcode == Opcode.SPUT_OBJECT) {
                            val value = ((insn as ReferenceInstruction).reference as StringReference).string
                            val fieldRef = (next as ReferenceInstruction).reference.toString()
                            stringMap[fieldRef] = value
                        }
                    }
                }
            }
            placeholderClasses += classDef.type
        }

        // ── Step 1B: appkiller / ObjectLogger 风格（A 没拿到时；对应 parse_appkiller_pairs）
        //    模式：sget-object FIELD … const-string "VALUE"（FIELD 在前 VALUE 在后，宽松配对）
        if (stringMap.isEmpty()) {
            classDefForEach { classDef ->
                val relevantMethods = classDef.methods.filter { method ->
                    val insns = method.instructionsOrNull ?: return@filter false
                    val isAppkiller = method.name == APPKILLER_METHOD &&
                        method.returnType == "V" && method.parameterTypes.isEmpty()
                    val hasLogger = insns.any { insn ->
                        insn.opcode == Opcode.INVOKE_VIRTUAL &&
                            (insn as? ReferenceInstruction)?.reference?.toString()
                                ?.contains(OBJECTLOGGER_SIG) == true
                    }
                    isAppkiller || hasLogger
                }
                if (relevantMethods.isEmpty()) return@classDefForEach
                relevantMethods.forEach { method ->
                    var pendingField: String? = null
                    method.instructions.forEach { insn ->
                        when (insn.opcode) {
                            Opcode.SGET_OBJECT -> {
                                val ref = (insn as ReferenceInstruction).reference
                                if (ref is FieldReference && ref.type == "Ljava/lang/String;")
                                    pendingField = ref.toString()
                            }
                            Opcode.CONST_STRING -> pendingField?.let { field ->
                                val value = ((insn as ReferenceInstruction).reference as StringReference).string
                                stringMap[field] = value
                                placeholderClasses += field.substringBefore("->")
                                pendingField = null
                            }
                            else -> Unit
                        }
                    }
                }
                placeholderClasses += classDef.type
            }
        }

        logger.info("pairip: ${stringMap.size} strings, ${placeholderClasses.size} placeholder classes")

        // ── Step 2: 替换使用方（对应 build_pattern_str）
        //    模式：const/4|const/16  +  sget-object FIELD(in stringMap)
        //    → const-string（用 sget 的寄存器），并删掉前面那条 const/4|const/16
        if (stringMap.isNotEmpty()) {
            classDefForEach { classDef ->
                if (classDef.type.startsWith(PAIRIP_PREFIX)) return@classDefForEach
                if (classDef.type in placeholderClasses) return@classDefForEach
                classDef.methods.forEach methods@{ method ->
                    val insns = method.instructionsOrNull?.toList() ?: return@methods
                    // (constIndex, sgetIndex, replacementSmali)
                    val edits = ArrayList<Triple<Int, Int, String>>()
                    insns.forEachIndexed { index, insn ->
                        if (insn.opcode != Opcode.SGET_OBJECT) return@forEachIndexed
                        val fieldRef = (insn as ReferenceInstruction).reference.toString()
                        val value = stringMap[fieldRef] ?: return@forEachIndexed
                        if (index == 0) return@forEachIndexed
                        val prev = insns[index - 1]
                        if (prev.opcode != Opcode.CONST_4 && prev.opcode != Opcode.CONST_16)
                            return@forEachIndexed
                        val reg = (insn as OneRegisterInstruction).registerA
                        edits += Triple(index - 1, index, "const-string v$reg, \"${value.toSmaliLiteral()}\"")
                    }
                    if (edits.isEmpty()) return@methods
                    val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@methods
                    val mutableMethod = mutableClass.methods.firstOrNull { m ->
                        m.name == method.name &&
                            m.returnType == method.returnType &&
                            m.parameterTypes.map { it.toString() } ==
                            method.parameterTypes.map { it.toString() }
                    } ?: return@methods
                    // 从后往前：先 replace sget，再删 const（避免索引偏移）
                    edits.sortedByDescending { it.second }.forEach { (constIndex, sgetIndex, smali) ->
                        mutableMethod.replaceInstruction(sgetIndex, smali)
                        mutableMethod.removeInstruction(constIndex)
                    }
                }
            }
        }

        // ── Step 3: 清空所有调用 VMRunner 的方法体
        classDefForEach { classDef ->
            if (classDef.type.startsWith(PAIRIP_PREFIX)) return@classDefForEach
            val targets = classDef.methods.filter { method ->
                method.instructionsOrNull?.any { insn ->
                    insn.opcode == Opcode.INVOKE_STATIC &&
                        (insn as? ReferenceInstruction)?.reference?.toString()
                            ?.contains(VMRUNNER_CLASS) == true
                } == true
            }
            if (targets.isEmpty()) return@classDefForEach
            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
            targets.forEach { method ->
                val mutableMethod = mutableClass.methods.firstOrNull { m ->
                    m.name == method.name &&
                        m.returnType == method.returnType &&
                        m.parameterTypes.map { it.toString() } ==
                        method.parameterTypes.map { it.toString() }
                } ?: return@forEach
                val count = mutableMethod.instructions.size
                mutableMethod.removeInstructions(0, count)
                mutableMethod.addInstructions(0, minimalReturnFor(method.returnType))
            }
        }

        // ── Step 4: 删除引用 Lcom/pairip/ 的指令（invoke / 字段访问，对应 py 阶段6 步骤2、3）
        //    必须在删空 clinit 之前：删掉 invoke pairip 后 clinit 才会变空
        classDefForEach { classDef ->
            if (classDef.type.startsWith(PAIRIP_PREFIX)) return@classDefForEach
            val hasRef = classDef.methods.any { method ->
                method.instructionsOrNull?.any { isPairipRef(it) } == true
            }
            if (!hasRef) return@classDefForEach
            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
            mutableClass.methods.forEach { method ->
                val insns = method.instructionsOrNull?.toList() ?: return@forEach
                val toRemove = ArrayList<Int>()
                insns.forEachIndexed { index, insn -> if (isPairipRef(insn)) toRemove += index }
                toRemove.sortedDescending().forEach { method.removeInstruction(it) }
            }
        }

        // ── Step 5: 删除只剩 return-void 的空 <clinit>（对应 remove_empty_methods）
        //    注意：只删"只有一条 return-void"的，有真实初始化逻辑的 clinit 绝不动
        classDefForEach { classDef ->
            if (classDef.type.startsWith(PAIRIP_PREFIX)) return@classDefForEach
            val hasEmptyClinit = classDef.methods.any { m ->
                m.name == "<clinit>" && m.isOnlyReturnVoid()
            }
            if (!hasEmptyClinit) return@classDefForEach
            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
            val target = mutableClass.methods.firstOrNull { m ->
                m.name == "<clinit>" && m.isOnlyReturnVoid()
            } ?: return@classDefForEach
            mutableClass.methods.remove(target)
        }

        // ── Step 6: 真删除 pairip 类 + 占位类（强制 FULL 模式，classMap.remove）
        forceFullBytecodeMode()
        val typesToRemove = HashSet<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(PAIRIP_PREFIX) || classDef.type in placeholderClasses)
                typesToRemove += classDef.type
        }
        val classMap = internalClassMap()
        var removed = 0
        typesToRemove.forEach { if (classMap.remove(it) != null) removed++ }
        logger.info("pairip: removed $removed classes")
    }
}
