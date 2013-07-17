package miniboxing.classloader

import java.net._
import java.io._
import miniboxing.tools.asm._
import miniboxing.tools.asm.tree._
import java.lang.{ClassLoader => JClassLoader}
import java.util.{List => JList}
import scala.collection.JavaConverters.asScalaBufferConverter
import java.util.ListIterator
import miniboxing.tools.asm.util._
import miniboxing.tools.asm.tree.analysis._
import scala.collection.mutable.Map
import miniboxing.tools.asm.optimiz._

/** Taken from http://stackoverflow.com/questions/6366288/how-to-change-default-class-loader-in-java */
class MiniboxingClassLoader(parent: ClassLoader) extends ClassLoader(parent) {

  val classes = Map.empty[String, Class[_]]
  lazy val constantFolder = new ConstantFolder()
  lazy val unreachableCode = new UnreachableCode()
  lazy val jumpReducer = new JumpReducer(null)
  lazy val jumpChainsColl = new JumpChainsCollapser(jumpReducer)


  def needsModifying(name: String): Boolean =
    // TODO: Extend to more parameters
    name.matches(".*_class_[0-9]$")

  def modifyClass(in: InputStream, oldname: String, newname: String): Array[Byte] = {
    val tparam = newname.last.toInt - '0'.toInt
    val cr = new ClassReader(in)
    val classNode = new ClassNode()
    cr.accept(classNode, 0)

    classNode.name = newname

    // Make the type tags static final
    val fieldNodes = classNode.fields.iterator()
    while(fieldNodes.hasNext()) {
      val fieldNode = fieldNodes.next()
      if (fieldNode.name.endsWith("_TypeTag")) {
        fieldNode.access |= Opcodes.ACC_FINAL;
        // TODO: Extend to more parameters, this only supports one
        fieldNode.value = new Integer(tparam)
      }
    }

    // Patch all the methods
    val methodNodes = classNode.methods.iterator()
    while(methodNodes.hasNext()) {
      val methodNode = methodNodes.next()
      val insnNodes = methodNode.instructions.iterator().asInstanceOf[ListIterator[AbstractInsnNode]]
      while (insnNodes.hasNext()) {
        insnNodes.next() match {
          case finst: FieldInsnNode =>
            // update owner to the new class
            finst.owner = finst.owner.replace(oldname, newname) // update names everywhere
            // and patch the code for the static final value
            if (finst.name.endsWith("_TypeTag")) {
              finst.getOpcode match {
                case Opcodes.GETFIELD =>
                  insnNodes.set(new InsnNode(Opcodes.POP));
                  val replNode = tparam match {
                    case 0 | 1 | 2 | 3 | 4 => new InsnNode(Opcodes.ICONST_0 + tparam)
                    case _ => new IntInsnNode(Opcodes.BIPUSH, tparam)
                  }
                  insnNodes.add(replNode) // Full expansion
                case Opcodes.PUTFIELD =>
                  insnNodes.set(new InsnNode(Opcodes.POP2));
              }
            }
          case tinst: TypeInsnNode if tinst.getOpcode() == Opcodes.NEW =>
            // patch up NEW calls
            if (tinst.desc.endsWith("_J"))
              // TODO: In-place replace this
              insnNodes.set(new TypeInsnNode(Opcodes.NEW, tinst.desc.replaceAll("_class_J", "_class_" + tparam)))
          case minst: MethodInsnNode =>
            // update owner to the new class
            minst.owner = minst.owner.replace(oldname, newname) // update names everywhere
            // patch up constructor call
            if (minst.name == "<init>")
              if (minst.owner.endsWith("_J"))
                // TODO: In-place replace this
                minst.owner = minst.owner.replaceAll("_class_J", "_class_" + tparam)
          case _ =>
        }
      }
    }

//    // Debugging:
//    System.err.println("================================BEFORE================================")
//    var printWriter = new PrintWriter(System.err);
//    var traceClassVisitor = new TraceClassVisitor(printWriter);
//    classNode.accept(traceClassVisitor);

    // Optimizing the hell out of that class:
    val iter = classNode.methods.iterator()
    while(iter.hasNext) {
      val mnode = iter.next()
      if (Util.hasBytecodeInstructions(mnode)) {
         Util.computeMaxLocalsMaxStack(mnode)
         jumpChainsColl.transform(mnode)
         constantFolder.transform(newname, mnode)
         unreachableCode.transform(newname, mnode)
         jumpChainsColl.transform(mnode)
      }
    }

//    System.err.println("================================AFTER================================")
//    printWriter = new PrintWriter(System.err);
//    traceClassVisitor = new TraceClassVisitor(printWriter);
//    classNode.accept(traceClassVisitor);
//
//    val analyzer = new Analyzer(new BasicVerifier)
//    for (methodNode <- methodNodes) {
//      analyzer.analyze(name, methodNode)
//    }

    val cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    classNode.accept(cw);
    var classBytes = cw.toByteArray

// DUMP CLASS
//    val output = new FileOutputStream(new File("/tmp/class-file"))
//    output.write(classBytes)

    classBytes
  }

  override def findClass(decodedName: String): Class[_] = classes get decodedName match {
    case Some(clazz) => clazz
    case None =>
      if (needsModifying(decodedName)) {
        try {
          //System.err.println("NEEDS MODIFYING: " + decodedName)
          val encodedName = decodedName.replace('.', '/')
          // TODO: Extend to more parameters
          val encodedTplName = encodedName.replaceAll("_class_[0-9]$", "_class_J")
          val templateBytes = super.getResourceAsStream(encodedTplName + ".class");
          if (templateBytes == null) {
            throw new ClassNotFoundException("Class " + encodedTplName + " not found. Sorry.");
          }
          val array = modifyClass(templateBytes, encodedTplName, encodedName);

          // store it
          val clazz = defineClass(decodedName, array, 0, array.length);
          classes += decodedName -> clazz
          //System.err.println("DONE")
          clazz
        } catch {
          case io: IOException =>
            throw new ClassNotFoundException(io.toString);
        }
      } else {
        super.findClass(decodedName);
      }
    }
}

object MiniboxingClassLoader {
  // TODO: Make this weak
  var classloaders = collection.mutable.Map.empty[ClassLoader, MiniboxingClassLoader]
  def classloader(ths: Any): MiniboxingClassLoader = {
    val crt_classloader = ths.getClass().getClassLoader()
    classloaders get (crt_classloader) match {
      case Some(classloader) => classloader
      case None =>
        val classloader = new MiniboxingClassLoader(crt_classloader)
        classloaders += crt_classloader -> classloader
        classloader
    }
  }
}
