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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

/*
 * Remove pairip protection —— 纯 bytecode patch
 * 还原被 pairip 混淆/加固的字符串并清除 pairip 字节码保护，逻辑对照 string_restorer.py。
 *
 * 字符串来源（三种并存）：
 *   A. com.pairip.application.Application        const-string + sput-object 配对（扫整个类）
 *   B. com.pairip.StartupLauncher.restoreString  同 A 格式（只扫这一个方法，避开 restoreMethod）
 *   C. appkiller / ObjectLogger 风格             sget-object + const-string（A/B 没拿到时兜底）
 *
 * 流程：
 *   Step 1  收集字符串映射 + 占位类（1A = 来源 A/B，1B = 来源 C）
 *   Step 2  替换使用方：const/4|const/16 + sget-object → const-string
 *   Step 3  清空所有调用 VMRunner 的方法体
 *   Step 4  删除引用 Lcom/pairip/ 的指令（invoke / 字段访问）
 *   Step 5  删除被掏空、只剩 return-void 的 <clinit>
 *   Step 6  强制 FULL 模式，真删除 pairip 类 + 占位类
 */

private val logger = Logger.getLogger("RemovePairip")

private const val PAIRIP_PREFIX = "Lcom/pairip/"
private const val APPLICATION_CLASS = "Lcom/pairip/application/Application;"
private const val STARTUP_LAUNCHER_CLASS = "Lcom/pairip/StartupLauncher;"
private const val VMRUNNER_CLASS = "Lcom/pairip/VMRunner;"
private const val APPKILLER_METHOD = "appkiller"
private const val OBJECTLOGGER_SIG = "/ObjectLogger;->logstring("

// StartupLauncher 里只有 restoreString() 是纯字符串来源（同 Application 格式）；
// restoreMethod() 是方法占位，绝不在这里碰，避免误收它的 Method 占位类。
private const val RESTORE_STRING_METHOD = "restoreString"

// 字符串映射的两个"纯字符串容器"来源：Application 整类 + StartupLauncher.restoreString()
private val STRING_SOURCE_CLASSES = setOf(APPLICATION_CLASS, STARTUP_LAUNCHER_CLASS)

/** 把字符串转成 smali const-string 字面量（转义特殊字符） */
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

/** 按返回类型生成最小返回指令（用于清空方法体） */
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

/** 指令是否引用 Lcom/pairip/（invoke 调用或字段访问） */
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
    default = true,
) {
    execute {
        val stringMap = HashMap<String, String>()          // 占位字段 → 原字符串
        val placeholderClasses = HashSet<String>()          // 待删除的占位类

        // ── Step 1A: 来源 A/B —— Application 整类 + StartupLauncher.restoreString()
        //    · 所有 sput-object 的目标类 → 占位类
        //    · const-string 紧跟 sput-object → 字符串映射
        //    （restoreMethod() 里 const-string 后面跟的是 invoke-static，不相邻，自动排除）
        classDefForEach { classDef ->
            if (classDef.type !in STRING_SOURCE_CLASSES) return@classDefForEach
            classDef.methods.forEach methods@{ method ->
                // StartupLauncher 只看 restoreString()，restoreMethod() 跳过
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

        // ── Step 1B: 来源 C —— appkiller / ObjectLogger 风格（仅当 A/B 没拿到时兜底）
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

        // ── Step 2: 替换使用方
        //    模式：const/4|const/16 + sget-object FIELD(in stringMap)
        //    → 把 sget-object 换成 const-string（沿用 sget 的寄存器），并删掉前面那条垃圾 const
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

        // ── Step 3: 清空所有调用 VMRunner 的方法体（改成最小返回）
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

        // ── Step 4: 删除引用 Lcom/pairip/ 的指令（invoke / 字段访问）
        //    必须在删空 clinit 之前：删掉 invoke pairip 后 clinit 才可能变空
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

        // ── Step 5: 删除只剩 return-void 的空 <clinit>
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

        // ── Step 6: 强制 FULL 模式，真删除 pairip 类 + 占位类（classMap.remove）
        forceFullBytecodeMode()
        val typesToRemove = HashSet<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(PAIRIP_PREFIX) || classDef.type in placeholderClasses)
                typesToRemove += classDef.type
        }
        val classMap = internalClassMap()
        var removed = 0
        typesToRemove.forEach { if (classMap.remove(it) != null) removed++ }

        // ── Step 7: 删除 pairip 整数常量类
        //    特征（全满足，最多删一个）：super 为 Object、无任何方法、无实例字段、
        //    仅 static final int 字段且数量 ≥ 2、且全 app 无任何指令引用该类。
        //    名字随机混淆，按特征匹配；零引用既防误删、也再次印证它是无用常量类。
        val constCandidates = LinkedHashSet<String>()
        classDefForEach { classDef ->
            if (classDef.superclass != "Ljava/lang/Object;") return@classDefForEach
            if (classDef.methods.any()) return@classDefForEach
            if (classDef.instanceFields.any()) return@classDefForEach
            val staticFields = classDef.staticFields.toList()
            if (staticFields.size < 2) return@classDefForEach
            val allFinalIntStatic = staticFields.all { f ->
                f.type == "I" &&
                        AccessFlags.FINAL.isSet(f.accessFlags) &&
                        AccessFlags.STATIC.isSet(f.accessFlags)
            }
            if (allFinalIntStatic) constCandidates += classDef.type
        }

        // 全 app 扫描：任一指令的引用文本里出现候选类 type，就说明被引用，剔除
        if (constCandidates.isNotEmpty()) {
            classDefForEach { classDef ->
                if (constCandidates.isEmpty()) return@classDefForEach
                classDef.methods.forEach { m ->
                    m.instructionsOrNull?.forEach { insn ->
                        val refText = (insn as? ReferenceInstruction)?.reference?.toString() ?: return@forEach
                        constCandidates.removeAll { type -> refText.contains(type) }
                    }
                }
            }
        }

        val constRemoved = constCandidates.firstOrNull()?.let { classMap.remove(it) != null } == true

        logger.info("pairip: removed $removed classes" + if (constRemoved) " + 1 const class" else "")
    }
}
