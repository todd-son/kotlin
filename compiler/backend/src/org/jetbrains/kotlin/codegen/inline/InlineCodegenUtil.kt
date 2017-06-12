/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.inline

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.MemberCodegen
import org.jetbrains.kotlin.codegen.binding.CodegenBinding
import org.jetbrains.kotlin.codegen.context.CodegenContext
import org.jetbrains.kotlin.codegen.context.CodegenContextUtil
import org.jetbrains.kotlin.codegen.context.InlineLambdaContext
import org.jetbrains.kotlin.codegen.context.MethodContext
import org.jetbrains.kotlin.codegen.intrinsics.*
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.codegen.`when`.WhenByEnumsMapping
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.fileClasses.*
import org.jetbrains.kotlin.fileClasses.JvmFileClassesProvider
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinder
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.org.objectweb.asm.tree.*
import org.jetbrains.org.objectweb.asm.util.Printer
import org.jetbrains.org.objectweb.asm.util.Textifier
import org.jetbrains.org.objectweb.asm.util.TraceMethodVisitor

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

import org.jetbrains.kotlin.resolve.jvm.AsmTypes.ENUM_TYPE
import org.jetbrains.kotlin.resolve.jvm.AsmTypes.JAVA_CLASS_TYPE


const val GENERATE_SMAP = true
const val API = Opcodes.ASM5
const val THIS = "this"
const val THIS_0 = "this$0"
const val FIRST_FUN_LABEL = "$$$$\$ROOT$$$$$"
const val NUMBERED_FUNCTION_PREFIX = "kotlin/jvm/functions/Function"
const val SPECIAL_TRANSFORMATION_NAME = "\$special"
const val INLINE_TRANSFORMATION_SUFFIX = "\$inlined"
const val INLINE_CALL_TRANSFORMATION_SUFFIX = "$" + INLINE_TRANSFORMATION_SUFFIX
const val INLINE_FUN_THIS_0_SUFFIX = "\$inline_fun"
const val INLINE_FUN_VAR_SUFFIX = "\$iv"
const val DEFAULT_LAMBDA_FAKE_CALL = "$$\$DEFAULT_LAMBDA_FAKE_CALL$$$"

const val CAPTURED_FIELD_FOLD_PREFIX = "$$$"
private const val `RECEIVER$0` = "receiver$0"
private const val NON_LOCAL_RETURN = "$$$$\$NON_LOCAL_RETURN$$$$$"
private const val CAPTURED_FIELD_PREFIX = "$"
private const val NON_CAPTURED_FIELD_PREFIX = "$$"
private const val INLINE_MARKER_CLASS_NAME = "kotlin/jvm/internal/InlineMarker"
private const val INLINE_MARKER_BEFORE_METHOD_NAME = "beforeInlineCall"
private const val INLINE_MARKER_AFTER_METHOD_NAME = "afterInlineCall"
private const val INLINE_MARKER_FINALLY_START = "finallyStart"

private const val INLINE_MARKER_FINALLY_END = "finallyEnd"

fun getMethodNode(
        classData: ByteArray,
        methodName: String,
        methodDescriptor: String,
        classInternalName: String
): SMAPAndMethodNode? {
    val cr = ClassReader(classData)
    var node: MethodNode? = null
    val debugInfo = arrayOfNulls<String>(2)
    val lines = IntArray(2)
    lines[0] = Integer.MAX_VALUE
    lines[1] = Integer.MIN_VALUE

    cr.accept(object : ClassVisitor(API) {

        override fun visitSource(source: String?, debug: String?) {
            super.visitSource(source, debug)
            debugInfo[0] = source
            debugInfo[1] = debug
        }

        override fun visitMethod(
                access: Int,
                name: String,
                desc: String,
                signature: String?,
                exceptions: Array<String>?
        ): MethodVisitor? {
            if (methodName == name && methodDescriptor == desc) {
                node = object : MethodNode(API, access, name, desc, signature, exceptions) {
                    override fun visitLineNumber(line: Int, start: Label) {
                        super.visitLineNumber(line, start)
                        lines[0] = Math.min(lines[0], line)
                        lines[1] = Math.max(lines[1], line)
                    }
                }
                return node
            }
            return null
        }
    }, ClassReader.SKIP_FRAMES or if (GENERATE_SMAP) 0 else ClassReader.SKIP_DEBUG)

    if (node == null) {
        return null
    }

    if (classId.asString() == classInternalName) {
        // Don't load source map for intrinsic array constructors
        debugInfo[0] = null
    }

    val smap = SMAPParser.parseOrCreateDefault(debugInfo[1], debugInfo[0], classInternalName, lines[0], lines[1])
    return SMAPAndMethodNode(node!!, smap)
}

fun initDefaultSourceMappingIfNeeded(
        context: CodegenContext<*>, codegen: MemberCodegen<*>, state: GenerationState
) {
    if (state.isInlineDisabled) return

    var parentContext: CodegenContext<*>? = context.parentContext
    while (parentContext != null) {
        if (parentContext.isInlineMethodContext) {
            //just init default one to one mapping
            codegen.orCreateSourceMapper
            break
        }
        parentContext = parentContext.parentContext
    }
}

fun findVirtualFile(state: GenerationState, classId: ClassId): VirtualFile? {
    return VirtualFileFinder.getInstance(state.project).findVirtualFileWithHeader(classId)
}

fun findVirtualFileImprecise(state: GenerationState, internalClassName: String): VirtualFile? {
    val packageFqName = JvmClassName.byInternalName(internalClassName).packageFqName
    val classNameWithDollars = internalClassName.substringAfterLast("/", internalClassName)
    //TODO: we cannot construct proper classId at this point, we need to read InnerClasses info from class file
    // we construct valid.package.name/RelativeClassNameAsSingleName that should work in compiler, but fails for inner classes in IDE
    return findVirtualFile(state, ClassId(packageFqName, Name.identifier(classNameWithDollars)))
}

fun getInlineName(
        codegenContext: CodegenContext<*>,
        typeMapper: KotlinTypeMapper,
        fileClassesManager: JvmFileClassesProvider
): String {
    return getInlineName(codegenContext, codegenContext.contextDescriptor, typeMapper, fileClassesManager)
}

private fun getInlineName(
        codegenContext: CodegenContext<*>,
        currentDescriptor: DeclarationDescriptor,
        typeMapper: KotlinTypeMapper,
        fileClassesProvider: JvmFileClassesProvider
): String {
    if (currentDescriptor is PackageFragmentDescriptor) {
        val file = DescriptorToSourceUtils.getContainingFile(codegenContext.contextDescriptor)

        val implementationOwnerType: Type? =
                if (file == null) {
                    CodegenContextUtil.getImplementationOwnerClassType(codegenContext)
                }
                else fileClassesProvider.getFileClassType(file)

        if (implementationOwnerType == null) {
            val contextDescriptor = codegenContext.contextDescriptor
            throw RuntimeException(
                    "Couldn't find declaration for " +
                    contextDescriptor.containingDeclaration!!.name + "." + contextDescriptor.name +
                    "; context: " + codegenContext
            )
        }

        return implementationOwnerType.internalName
    }
    else if (currentDescriptor is ClassifierDescriptor) {
        return typeMapper.mapType(currentDescriptor).internalName
    }
    else if (currentDescriptor is FunctionDescriptor) {
        val descriptor = typeMapper.bindingContext.get(CodegenBinding.CLASS_FOR_CALLABLE, currentDescriptor)
        if (descriptor != null) {
            return typeMapper.mapType(descriptor).internalName
        }
    }

    //TODO: add suffix for special case
    val suffix = if (currentDescriptor.name.isSpecial) "" else currentDescriptor.name.asString()


    return getInlineName(codegenContext, currentDescriptor.containingDeclaration!!, typeMapper, fileClassesProvider) + "$" + suffix
}

fun isInvokeOnLambda(owner: String, name: String): Boolean {
    return OperatorNameConventions.INVOKE.asString() == name &&
           owner.startsWith(NUMBERED_FUNCTION_PREFIX) &&
           isInteger(owner.substring(NUMBERED_FUNCTION_PREFIX.length))
}

fun isAnonymousConstructorCall(internalName: String, methodName: String): Boolean {
    return "<init>" == methodName && isAnonymousClass(internalName)
}

fun isWhenMappingAccess(internalName: String, fieldName: String): Boolean {
    return fieldName.startsWith(WhenByEnumsMapping.MAPPING_ARRAY_FIELD_PREFIX) && internalName.endsWith(WhenByEnumsMapping.MAPPINGS_CLASS_NAME_POSTFIX)
}

fun isAnonymousSingletonLoad(internalName: String, fieldName: String): Boolean {
    return JvmAbi.INSTANCE_FIELD == fieldName && isAnonymousClass(internalName)
}

fun isAnonymousClass(internalName: String): Boolean {
    val shortName = getLastNamePart(internalName)
    val index = shortName.lastIndexOf("$")

    if (index < 0) {
        return false
    }

    val suffix = shortName.substring(index + 1)
    return isInteger(suffix)
}

private fun getLastNamePart(internalName: String): String {
    val index = internalName.lastIndexOf("/")
    return if (index < 0) internalName else internalName.substring(index + 1)
}

fun wrapWithMaxLocalCalc(methodNode: MethodNode): MethodVisitor {
    return MaxStackFrameSizeAndLocalsCalculator(API, methodNode.access, methodNode.desc, methodNode)
}

private fun isInteger(string: String): Boolean {
    if (string.isEmpty()) {
        return false
    }

    for (i in 0..string.length - 1) {
        if (!Character.isDigit(string[i])) {
            return false
        }
    }

    return true
}

fun isCapturedFieldName(fieldName: String): Boolean {
    // TODO: improve this heuristic
    return fieldName.startsWith(CAPTURED_FIELD_PREFIX) && !fieldName.startsWith(NON_CAPTURED_FIELD_PREFIX) ||
           THIS_0 == fieldName ||
           `RECEIVER$0` == fieldName
}

fun isReturnOpcode(opcode: Int): Boolean {
    return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
}

//marked return could be either non-local or local in case of labeled lambda self-returns
fun isMarkedReturn(returnIns: AbstractInsnNode): Boolean {
    return getMarkedReturnLabelOrNull(returnIns) != null
}

fun getMarkedReturnLabelOrNull(returnInsn: AbstractInsnNode): String? {
    if (!isReturnOpcode(returnInsn.opcode)) {
        return null
    }
    val previous = returnInsn.previous
    if (previous is MethodInsnNode) {
        val marker = previous
        if (NON_LOCAL_RETURN == marker.owner) {
            return marker.name
        }
    }
    return null
}

fun generateGlobalReturnFlag(iv: InstructionAdapter, labelName: String) {
    iv.invokestatic(NON_LOCAL_RETURN, labelName, "()V", false)
}

fun getReturnType(opcode: Int): Type {
    when (opcode) {
        Opcodes.RETURN -> return Type.VOID_TYPE
        Opcodes.IRETURN -> return Type.INT_TYPE
        Opcodes.DRETURN -> return Type.DOUBLE_TYPE
        Opcodes.FRETURN -> return Type.FLOAT_TYPE
        Opcodes.LRETURN -> return Type.LONG_TYPE
        else -> return AsmTypes.OBJECT_TYPE
    }
}

fun insertNodeBefore(from: MethodNode, to: MethodNode, beforeNode: AbstractInsnNode) {
    val iterator = from.instructions.iterator()
    while (iterator.hasNext()) {
        val next = iterator.next()
        to.instructions.insertBefore(beforeNode, next)
    }
}

fun createEmptyMethodNode(): MethodNode {
    return MethodNode(API, 0, "fake", "()V", null, null)
}

fun firstLabelInChain(node: LabelNode): LabelNode {
    var curNode = node
    while (curNode.previous is LabelNode) {
        curNode = curNode.previous as LabelNode
    }
    return curNode
}

fun getNodeText(node: MethodNode?): String {
    val textifier = Textifier()
    if (node == null) {
        return "Not generated"
    }
    node.accept(TraceMethodVisitor(textifier))
    val sw = StringWriter()
    textifier.print(PrintWriter(sw))
    sw.flush()
    return node.name + " " + node.desc + ":\n" + sw.buffer.toString()
}

fun getInsnText(node: AbstractInsnNode?): String {
    if (node == null) return "<null>"
    val textifier = Textifier()
    node.accept(TraceMethodVisitor(textifier))
    val sw = StringWriter()
    textifier.print(PrintWriter(sw))
    sw.flush()
    return sw.toString().trim { it <= ' ' }
}

fun getInsnOpcodeText(node: AbstractInsnNode?): String {
    return if (node == null) "null" else Printer.OPCODES[node.opcode]
}

internal /* package */ fun buildClassReaderByInternalName(state: GenerationState, internalName: String): ClassReader {
    //try to find just compiled classes then in dependencies
    try {
        val outputFile = state.factory.get(internalName + ".class")
        if (outputFile != null) {
            return ClassReader(outputFile.asByteArray())
        }
        val file = findVirtualFileImprecise(state, internalName)
        if (file != null) {
            return ClassReader(file.contentsToByteArray())
        }
        throw RuntimeException("Couldn't find virtual file for " + internalName)
    }
    catch (e: IOException) {
        throw RuntimeException(e)
    }

}

fun generateFinallyMarker(v: InstructionAdapter, depth: Int, start: Boolean) {
    v.iconst(depth)
    v.invokestatic(INLINE_MARKER_CLASS_NAME, if (start) INLINE_MARKER_FINALLY_START else INLINE_MARKER_FINALLY_END, "(I)V", false)
}

fun isFinallyEnd(node: AbstractInsnNode): Boolean {
    return isFinallyMarker(node, INLINE_MARKER_FINALLY_END)
}

fun isFinallyStart(node: AbstractInsnNode): Boolean {
    return isFinallyMarker(node, INLINE_MARKER_FINALLY_START)
}

fun isFinallyMarker(node: AbstractInsnNode?): Boolean {
    return node != null && (isFinallyStart(node) || isFinallyEnd(node))
}

private fun isFinallyMarker(node: AbstractInsnNode, name: String): Boolean {
    if (node !is MethodInsnNode) return false
    val method = node
    return INLINE_MARKER_CLASS_NAME == method.owner && name == method.name
}

fun isFinallyMarkerRequired(context: MethodContext): Boolean {
    return context.isInlineMethodContext || context is InlineLambdaContext
}

fun getConstant(ins: AbstractInsnNode): Int {
    val opcode = ins.opcode
    if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5) {
        return opcode - Opcodes.ICONST_0
    }
    else if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
        return (ins as IntInsnNode).operand
    }
    else {
        val index = ins as LdcInsnNode
        return index.cst as Int
    }
}

fun removeFinallyMarkers(intoNode: MethodNode) {
    val instructions = intoNode.instructions
    var curInstr: AbstractInsnNode? = instructions.first
    while (curInstr != null) {
        if (isFinallyMarker(curInstr)) {
            val marker = curInstr
            //just to assert
            getConstant(marker.previous)
            curInstr = curInstr.next
            instructions.remove(marker.previous)
            instructions.remove(marker)
            continue
        }
        curInstr = curInstr.next
    }
}

fun addInlineMarker(v: InstructionAdapter, isStartNotEnd: Boolean) {
    v.visitMethodInsn(
            Opcodes.INVOKESTATIC, INLINE_MARKER_CLASS_NAME,
            if (isStartNotEnd) INLINE_MARKER_BEFORE_METHOD_NAME else INLINE_MARKER_AFTER_METHOD_NAME,
            "()V", false
    )
}

fun isInlineMarker(insn: AbstractInsnNode): Boolean {
    return isInlineMarker(insn, null)
}

private fun isInlineMarker(insn: AbstractInsnNode, name: String?): Boolean {
    if (insn !is MethodInsnNode) {
        return false
    }

    val methodInsnNode = insn
    return insn.getOpcode() == Opcodes.INVOKESTATIC &&
           methodInsnNode.owner == INLINE_MARKER_CLASS_NAME &&
           if (name != null)
               methodInsnNode.name == name
           else
               methodInsnNode.name == INLINE_MARKER_BEFORE_METHOD_NAME || methodInsnNode.name == INLINE_MARKER_AFTER_METHOD_NAME
}

fun isBeforeInlineMarker(insn: AbstractInsnNode): Boolean {
    return isInlineMarker(insn, INLINE_MARKER_BEFORE_METHOD_NAME)
}

fun isAfterInlineMarker(insn: AbstractInsnNode): Boolean {
    return isInlineMarker(insn, INLINE_MARKER_AFTER_METHOD_NAME)
}

fun getLoadStoreArgSize(opcode: Int): Int {
    return if (opcode == Opcodes.DSTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.DLOAD || opcode == Opcodes.LLOAD) 2 else 1
}

fun isStoreInstruction(opcode: Int): Boolean {
    return opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
}

fun calcMarkerShift(parameters: Parameters, node: MethodNode): Int {
    val markerShiftTemp = getIndexAfterLastMarker(node)
    return markerShiftTemp - parameters.realParametersSizeOnStack + parameters.argsSizeOnStack
}

private fun getIndexAfterLastMarker(node: MethodNode): Int {
    var result = -1
    for (variable in node.localVariables) {
        if (isFakeLocalVariableForInline(variable.name)) {
            result = Math.max(result, variable.index + 1)
        }
    }
    return result
}

fun isFakeLocalVariableForInline(name: String): Boolean {
    return name.startsWith(JvmAbi.LOCAL_VARIABLE_NAME_PREFIX_INLINE_FUNCTION) || name.startsWith(JvmAbi.LOCAL_VARIABLE_NAME_PREFIX_INLINE_ARGUMENT)
}

fun isThis0(name: String): Boolean {
    return THIS_0 == name
}

fun isSpecialEnumMethod(functionDescriptor: FunctionDescriptor): Boolean {
    val containingDeclaration = functionDescriptor.containingDeclaration as? PackageFragmentDescriptor ?: return false
    if (containingDeclaration.fqName != KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAME) {
        return false
    }
    if (functionDescriptor.typeParameters.size != 1) {
        return false
    }
    val name = functionDescriptor.name.asString()
    val parameters = functionDescriptor.valueParameters
    return "enumValues" == name && parameters.size == 0 || "enumValueOf" == name && parameters.size == 1 && KotlinBuiltIns.isString(parameters[0].type)
}

fun createSpecialEnumMethodBody(
        codegen: ExpressionCodegen,
        name: String,
        type: KotlinType,
        typeMapper: KotlinTypeMapper
): MethodNode {
    val isValueOf = "enumValueOf" == name
    val invokeType = typeMapper.mapType(type)
    val desc = getSpecialEnumFunDescriptor(invokeType, isValueOf)
    val node = MethodNode(API, Opcodes.ACC_STATIC, "fake", desc, null, null)
    codegen.putReifiedOperationMarkerIfTypeIsReifiedParameter(type, ReifiedTypeInliner.OperationKind.ENUM_REIFIED, InstructionAdapter(node))
    if (isValueOf) {
        node.visitInsn(Opcodes.ACONST_NULL)
        node.visitVarInsn(Opcodes.ALOAD, 0)

        node.visitMethodInsn(Opcodes.INVOKESTATIC, ENUM_TYPE.internalName, "valueOf",
                             Type.getMethodDescriptor(ENUM_TYPE, JAVA_CLASS_TYPE, AsmTypes.JAVA_STRING_TYPE), false)
    }
    else {
        node.visitInsn(Opcodes.ICONST_0)
        node.visitTypeInsn(Opcodes.ANEWARRAY, ENUM_TYPE.internalName)
    }
    node.visitInsn(Opcodes.ARETURN)
    node.visitMaxs(if (isValueOf) 3 else 2, if (isValueOf) 1 else 0)
    return node
}

fun getSpecialEnumFunDescriptor(type: Type, isValueOf: Boolean): String {
    return if (isValueOf) Type.getMethodDescriptor(type, AsmTypes.JAVA_STRING_TYPE) else Type.getMethodDescriptor(AsmUtil.getArrayType(type))
}