package app.patches.pairip.sjshb57

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

/*
 * pairip 字符串还原 + VMRunner 清空 + 占位类/pairip 类清理（Morphe，纯字节码）
 * 逻辑严格对照 string_restorer.py。
 */

private val logger = Logger.getLogger("RemovePairip")

private const val PAIRIP_PREFIX = "Lcom/pairip/"
private const val APPLICATION_CLASS = "Lcom/pairip/application/Application;"
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

@Suppress("unused")
val removePairipPatch = bytecodePatch(
    name = "Remove pairip protection",
    description = "Restores obfuscated strings and removes pairip bytecode protection.",
    default = true,
) {
    compatibleWith(Compatibility(packageName = "com.twitter.android", name = "Twitter / X"))

    execute {
        val stringMap = HashMap<String, String>()
        val placeholderClasses = HashSet<String>()

        // ── Step 1A: Application 类（对应 parse_application_smali + parse_application_classes）
        //    - 所有 sput-object 的目标类 → 占位类
        //    - const-string 紧跟 sput-object → 字符串映射
        classDefForEach { classDef ->
            if (classDef.type != APPLICATION_CLASS) return@classDefForEach
            classDef.methods.forEach methods@{ method ->
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
            placeholderClasses += APPLICATION_CLASS
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

        // ── Step 4: 清空 pairip 类 + 占位类（转 MutableClass，从原 DEX 剥离成空壳）
        val typesToClear = HashSet<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(PAIRIP_PREFIX) || classDef.type in placeholderClasses)
                typesToClear += classDef.type
        }
        var cleared = 0
        typesToClear.forEach { type ->
            val mutableClass = mutableClassDefByOrNull(type) ?: return@forEach
            mutableClass.methods.clear()
            mutableClass.fields.clear()
            cleared++
        }
        logger.info("pairip: cleared $cleared classes")
    }
}
