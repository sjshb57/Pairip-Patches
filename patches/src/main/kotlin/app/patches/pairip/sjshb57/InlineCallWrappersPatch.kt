package app.patches.pairip.sjshb57

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

/*
 * 内联 pairip 的 "call wrapper" 桩，去除这一层间接调用保护。
 *
 * pairip 把方法体里普通的 invoke-virtual/static/... 包装成一个 static synthetic 转发桩
 * （名字带 $<数字>，如 getAction$001 / equals$002 / concat$007），调用点改成 invoke-static 调桩。
 * 本补丁把调用点还原成桩里那条真实调用，再删掉桩。
 *
 * 识别（从“已还原的正常方法体”出发，不盲扫全 app static 方法）：
 *   遍历所有方法体里的 invoke-static 调用点，目标方法满足：
 *     ① 名字带 $<数字> 后缀（硬条件）
 *     ② 定义在本 app（classDefByOrNull 能找到）
 *     ③ 桩体是纯转发结构：唯一一条真实 invoke-XXX(/range) + 可选 move-result* + return*
 *   三者都满足才判定为桩。
 *
 * 内联：调用点 "invoke-static {寄存器列表}, 桩" 就地换成 "invoke-<桩里真实opcode> {同一寄存器列表}, 真实目标"。
 *   - 寄存器列表完全不动；后续 move-result* 不动。
 *   - opcode 取桩体真实调用的类别（virtual/super/direct/static/interface），range 与否由调用点格式决定
 *     （调用点 35c → 非 range，调用点 3rc → range）。
 *
 * 删桩：所有调用点替换完（第一遍已找全所有含桩调用的类），按桩定义类删掉桩方法。
 *
 * 依赖：建立在补丁二把 $c 抽离方法还原回正常方法体之后才有意义，故排在补丁二之后。
 */

private val logger = Logger.getLogger("InlineCallWrappers")

/** 桩名后缀 $<数字>，如 $001 / $007 */
private val WRAPPER_NAME = Regex("""\$\d+$""")

private val INVOKE_OPCODES = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
    Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
    Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
)
private val MOVE_RESULT_OPCODES = setOf(
    Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT,
)
private val RETURN_OPCODES = setOf(
    Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.RETURN_VOID,
)

/** 桩体真实 invoke 的 opcode + 调用点是否 range → 内联后应使用的 opcode */
private fun realOpcodeFor(stubInvoke: Opcode, callSiteRange: Boolean): Opcode? {
    val (base, range) = when (stubInvoke) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> Opcode.INVOKE_VIRTUAL to Opcode.INVOKE_VIRTUAL_RANGE
        Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> Opcode.INVOKE_SUPER to Opcode.INVOKE_SUPER_RANGE
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> Opcode.INVOKE_DIRECT to Opcode.INVOKE_DIRECT_RANGE
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> Opcode.INVOKE_STATIC to Opcode.INVOKE_STATIC_RANGE
        Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> Opcode.INVOKE_INTERFACE to Opcode.INVOKE_INTERFACE_RANGE
        else -> return null
    }
    return if (callSiteRange) range else base
}

/** 桩体真实调用：opcode 类别 + 目标方法引用 */
private class ParsedWrapper(val realOpcode: Opcode, val realTarget: MethodReference)

/** 解析一个候选桩方法引用，纯转发才返回结果，否则 null */
private fun BytecodePatchContext.parseWrapper(ref: MethodReference): ParsedWrapper? {
    val classDef = classDefByOrNull(ref.definingClass) ?: return null // 不在本 app
    val method = classDef.methods.firstOrNull { m ->
        m.name == ref.name &&
                m.returnType == ref.returnType &&
                m.parameterTypes.map { it.toString() } == ref.parameterTypes.map { it.toString() }
    } ?: return null
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val insns = method.implementation?.instructions?.toList() ?: return null

    // 纯转发：恰好一条真实 invoke，其余只能是 move-result* / return*
    val invokes = insns.filter { it.opcode in INVOKE_OPCODES }
    if (invokes.size != 1) return null
    val realInvoke = invokes[0]
    if (!insns.all { it === realInvoke || it.opcode in MOVE_RESULT_OPCODES || it.opcode in RETURN_OPCODES }) return null

    val realTarget = (realInvoke as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return ParsedWrapper(realInvoke.opcode, realTarget)
}

/** 用调用点的寄存器 + 桩里的真实 opcode/目标，构造内联后的指令 */
private fun buildInlined(callSite: Instruction, parsed: ParsedWrapper): BuilderInstruction? {
    val newOpcode = realOpcodeFor(parsed.realOpcode, callSite.opcode == Opcode.INVOKE_STATIC_RANGE) ?: return null
    return when (callSite) {
        is BuilderInstruction35c -> BuilderInstruction35c(
            newOpcode,
            callSite.registerCount,
            callSite.registerC, callSite.registerD, callSite.registerE,
            callSite.registerF, callSite.registerG,
            parsed.realTarget,
        )
        is BuilderInstruction3rc -> BuilderInstruction3rc(
            newOpcode,
            callSite.startRegister,
            callSite.registerCount,
            parsed.realTarget,
        )
        else -> null
    }
}

@Suppress("unused")
val inlineCallWrappersPatch = bytecodePatch(
    name = "Inline pairip call wrappers",
    description = "Inlines pairip's static call-wrapper stubs ($<number>) back into their call sites and removes the stubs.",
    default = true,
) {
    dependsOn(restoreExtractedMethodsPatch)

    execute {
        val cache = HashMap<String, ParsedWrapper?>()

        fun resolve(insn: Instruction): ParsedWrapper? {
            if (insn.opcode != Opcode.INVOKE_STATIC && insn.opcode != Opcode.INVOKE_STATIC_RANGE) return null
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return null
            if (!WRAPPER_NAME.containsMatchIn(ref.name)) return null
            return cache.getOrPut(ref.toString()) { parseWrapper(ref) }
        }

        // 只处理补丁二还原过的主类（pairip 真正动过的），按 type 直接取；正常类一律不碰。
        var inlined = 0
        val usedWrappers = LinkedHashSet<MethodReference>()
        restoredHostTypes.forEach { type ->
            val mutableClass = mutableClassDefByOrNull(type) ?: return@forEach
            mutableClass.methods.forEach { method ->
                val impl = method.implementation ?: return@forEach
                // 先收集再替换，避免遍历中改动指令列表
                val replacements = ArrayList<Pair<Int, BuilderInstruction>>()
                impl.instructions.forEachIndexed { index, insn ->
                    val parsed = resolve(insn) ?: return@forEachIndexed
                    val newInsn = buildInlined(insn, parsed) ?: return@forEachIndexed
                    replacements += index to newInsn
                    usedWrappers += (insn as ReferenceInstruction).reference as MethodReference
                }
                replacements.forEach { (index, newInsn) ->
                    impl.replaceInstruction(index, newInsn)
                    inlined++
                }
            }
        }

        // 删桩：所有调用点已替换，按桩定义类删掉桩方法
        var removed = 0
        usedWrappers.groupBy { it.definingClass }.forEach { (classType, refs) ->
            val mutableClass = mutableClassDefByOrNull(classType) ?: return@forEach
            refs.forEach { ref ->
                val stub = mutableClass.methods.firstOrNull { m ->
                    m.name == ref.name &&
                            m.returnType == ref.returnType &&
                            m.parameterTypes.map { it.toString() } == ref.parameterTypes.map { it.toString() }
                }
                if (stub != null && mutableClass.methods.remove(stub)) removed++
            }
        }

        logger.info("inlined $inlined call sites, removed $removed wrapper methods")
    }
}
