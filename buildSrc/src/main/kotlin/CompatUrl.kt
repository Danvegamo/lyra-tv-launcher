import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * NewPipeExtractor llama a URLDecoder.decode(String, Charset) y URLEncoder.encode(String, Charset) (Java 10), que no existen en Android 9
 * (API 28, el Fire TV Stick 4K Max). Este paso de compilación reescribe esas llamadas hacia `co.davidvega.launcher.sistema.CompatUrl`.
 */
abstract class CompatUrlFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitMethodInsn(opcode: Int, owner: String?, n: String?, desc: String?, isInterface: Boolean) {
                        if (opcode == Opcodes.INVOKESTATIC && desc == "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;" &&
                            ((owner == "java/net/URLDecoder" && n == "decode") || (owner == "java/net/URLEncoder" && n == "encode"))) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "co/davidvega/launcher/sistema/CompatUrl", n, desc, false)
                        } else super.visitMethodInsn(opcode, owner, n, desc, isInterface)
                    }
                }
            }
        }

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className.startsWith("org.schabi.newpipe.extractor.") || classData.className.startsWith("org.jsoup.")
}
