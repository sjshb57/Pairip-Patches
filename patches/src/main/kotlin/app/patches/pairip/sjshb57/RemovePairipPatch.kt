package app.patches.pairip.sjshb57

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

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

@Suppress("unused")
val removePairipPatch = bytecodePatch(
    name = "Remove pairip protection",
    description = "Restores obfuscated strings and removes pairip bytecode protection.",
    default = true,
) {
    compatibleWith("com.twitter.android")

    execute {
        val stringMap = HashMap<String, String>()
        val placeholderClasses = HashSet<String>()

        // Step 1A: Application 类 const-string + sput-object 配对
        classDefForEach { classDef ->
            if (classDef.type != APPLICATION_CLASS) return@classDefForEach
            classDef.methods.forEach methods@{ method ->
                val insns = method.instructionsOrNull?.toList() ?: return@methods
                for (i in 0 until insns.size - 1) {
                    val cur = insns[i]
                    val next = insns[i + 1]
                    if (cur.opcode != Opcode.CONST_STRING) continue
                    if (next.opcode != Opcode.SPUT_OBJECT) continue
                    val value = ((cur as ReferenceInstruction).reference as StringReference).string
                    val fieldRef = (next as ReferenceInstruction).reference.toString()
                    stringMap[fieldRef] = value
                    placeholderClasses += fieldRef.substringBefore("->")
                }
            }
            placeholderClasses += APPLICATION_CLASS
        }

        // Step 1B: appkiller / ObjectLogger 风格（fallback）
        if (stringMap.isEmpty()) {
            classDefForEach { classDef ->
                val relevantMethods = classDef.methods.filter { method ->
                    val insns = method.instructionsOrNull ?: return@filter false
                    val isAppkiller = method.name == APPKILLER_METHOD &&
                        method.returnType == "V" &&
                        method.parameterTypes.isEmpty()
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

        // Step 2: sget-object → const-string
        if (stringMap.isNotEmpty()) {
            classDefForEach { classDef ->
                if (classDef.type.startsWith(PAIRIP_PREFIX)) return@classDefForEach
                if (classDef.type in placeholderClasses) return@classDefForEach
                classDef.methods.forEach methods@{ method ->
                    val insns = method.instructionsOrNull?.toList() ?: return@methods
                    val edits = ArrayList<Pair<Int, String>>()
                    insns.forEachIndexed { index, insn ->
                        if (insn.opcode != Opcode.SGET_OBJECT) return@forEachIndexed
                        val fieldRef = (insn as ReferenceInstruction).reference.toString()
                        val value = stringMap[fieldRef] ?: return@forEachIndexed
                        val reg = (insn as OneRegisterInstruction).registerA
                        edits += index to "const-string v$reg, \"${value.toSmaliLiteral()}\""
                    }
                    if (edits.isEmpty()) return@methods
                    val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@methods
                    val mutableMethod = mutableClass.methods.firstOrNull { m ->
                        m.name == method.name &&
                            m.returnType == method.returnType &&
                            m.parameterTypes.map { it.toString() } ==
                            method.parameterTypes.map { it.toString() }
                    } ?: return@methods
                    edits.forEach { (index, smali) -> mutableMethod.replaceInstruction(index, smali) }
                }
            }
        }

        // Step 3: 清空 VMRunner 方法体
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

        // Step 4: 删除 pairip 类和占位类
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
