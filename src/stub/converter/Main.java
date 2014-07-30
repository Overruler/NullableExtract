package stub.converter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.TypePath;
import jdk.internal.org.objectweb.asm.TypeReference;
import jdk.internal.org.objectweb.asm.signature.SignatureReader;
import jdk.internal.org.objectweb.asm.signature.SignatureVisitor;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TypeAnnotationNode;
import jdk.internal.org.objectweb.asm.util.CheckSignatureAdapter;
import jdk.internal.org.objectweb.asm.util.TraceSignatureVisitor;
import utils.lists2.ArrayList;
import utils.lists2.Files;
import utils.lists2.HashMap;
import utils.lists2.HashMap.Entry;
import utils.lists2.HashSet;
import utils.lists2.List;
import utils.lists2.Paths;
import utils.streams2.Streams;

public class Main {

	private static final String TEST_PROJECT_PATH = "_pasted_code_";

	public static void main(String[] args) {
		Path path =
			Paths.get(
				System.getProperty("user.home"),
				"Downloads",
				"checker-framework",
				"checker-framework-1.8.3",
				"checker",
				"dist",
				"jdk8.jar");
		try {
			HashSet<String> packages = new HashSet<>();
			HashMap<String, byte[]> map = Streams.jarFile(path).filter(Main::filter).toMap(Main::name, Main::readFully);
			for(Entry<String, byte[]> entry : map.entrySet()) {
				if(entry.lhs.endsWith(".class") == false) {
					continue;
				}
				ClassReader cr = new ClassReader(new ByteArrayInputStream(entry.rhs));
				ClassNode cn = new ClassNode();
				cr.accept(cn, ClassReader.SKIP_DEBUG);
				String packagePart = cn.name.substring(0, cn.name.lastIndexOf('/'));
				String currentPackage = "package " + packagePart.replace('/', '.') + ";\n";
				StringBuilder buf = new StringBuilder();
				buf.append(currentPackage);
				if(cn.name.endsWith("java/lang/Class")) {
					System.out.println("BREAK");
				}
				int methodsFound = processClass(cn, buf);
				if(methodsFound > 0) {
					output(entry.lhs, buf.toString());
					if(packages.contains(currentPackage) == false) {
						outputExtra(packagePart + "/Nullable.java", currentPackage +
						"@java.lang.annotation.Target({ java.lang.annotation.ElementType.TYPE_USE })\n" +
						"public @interface Nullable {}\n");
						packages.add(currentPackage);
					}
					if(cn.name.endsWith("java/lang/Thread")) {
						outputExtra(
							"java/lang/Thread$UncaughtExceptionHandler.java",
							"package java.lang;\npublic interface Thread$UncaughtExceptionHandler {}\n");
					}
				}
			}
		} catch(IOException e) {
			e.printStackTrace();
		}
	}
	private static int processClass(ClassNode cn, StringBuilder buf) {
		int methodAccessMask = processNameAndKind(cn, buf);
		String currentClass = cn.name.substring(cn.name.lastIndexOf('/') + 1);
		int methodsFound = processNameAndContents(cn, buf, methodAccessMask, currentClass);
		return methodsFound;
	}
	private static int
		processNameAndContents(ClassNode cn, StringBuilder buf, int methodAccessMask, String currentClass) {
		buf.append(currentClass);
		buf.append(" {\n");
		int methodsFound = processAllMethods(cn, buf, methodAccessMask, currentClass);
		buf.append("}\n");
		return methodsFound;
	}
	private static int processNameAndKind(ClassNode cn, StringBuilder buf) {
		int methodAccessMask;
		if((cn.access & Opcodes.ACC_ANNOTATION) != 0) {
			methodAccessMask = ~Opcodes.ACC_PUBLIC;
			appendAccess(buf, cn.access & ~Opcodes.ACC_SUPER);
			buf.append("@interface ");
		} else if((cn.access & Opcodes.ACC_INTERFACE) != 0) {
			methodAccessMask = ~Opcodes.ACC_PUBLIC & ~Opcodes.ACC_ABSTRACT;
			appendAccess(buf, cn.access & ~Opcodes.ACC_ABSTRACT);
			buf.append("interface ");
		} else if((cn.access & Opcodes.ACC_ENUM) == 0) {
			methodAccessMask = ~Opcodes.ACC_TRANSIENT & ~Opcodes.ACC_SYNCHRONIZED;
			appendAccess(buf, cn.access & ~Opcodes.ACC_SUPER);
			buf.append("class ");
		} else {
			appendAccess(buf, cn.access & ~Opcodes.ACC_SUPER);
			methodAccessMask = ~Opcodes.ACC_TRANSIENT;
		}
		return methodAccessMask;
	}
	private static int processAllMethods(ClassNode cn, StringBuilder buf, int methodAccessMask, String currentClass) {
		ArrayList<String> sharedParams = new ArrayList<>();
		if(cn.signature != null) {
			SignatureVisitor v = new CheckSignatureAdapter(Opcodes.ASM5, CheckSignatureAdapter.CLASS_SIGNATURE, null) {

				@Override
				public void visitFormalTypeParameter(final String name) {
					super.visitFormalTypeParameter(name);
					sharedParams.add(name);
				}
			};
			SignatureReader r = new SignatureReader(cn.signature);
			r.accept(v);
			System.out.println(sharedParams);
		}
		int methodsFound = 0;
		for(MethodNode method : cn.methods) {
			List<AnnotationNode> annotations =
				collect(method.visibleParameterAnnotations).addAll(collect(method.invisibleParameterAnnotations)).removeIf(
					Main::keepNullable);
			List<TypeAnnotationNode> typeAnnotations =
				asList(method.visibleTypeAnnotations).addAll(asList(method.invisibleTypeAnnotations)).removeIf(
					Main::keepNullable);
			if(annotations.isEmpty() && typeAnnotations.isEmpty()) {
				continue;
			}
			if((method.access & Opcodes.ACC_SYNTHETIC) != 0) {
				continue;
			}
			String descriptor = method.desc;
			Type returnType = Type.getReturnType(descriptor);
			List<Type> argumentTypes = asList(Type.getArgumentTypes(descriptor));
			ArrayList<String> params = sharedParams.toArrayList();
			if(method.signature != null) {
				TraceSignatureVisitor trace = new TraceSignatureVisitor(0);
				SignatureVisitor v =
					new CheckSignatureAdapter(Opcodes.ASM5, CheckSignatureAdapter.METHOD_SIGNATURE, trace) {

						@Override
						public void visitFormalTypeParameter(final String name) {
							super.visitFormalTypeParameter(name);
							params.add(name);
						}
					};
				SignatureReader r = new SignatureReader(method.signature);
				r.accept(v);
				String declaration = trace.getDeclaration();
				String returnType2 = trace.getReturnType();
				System.out.println(params);
				System.out.println(declaration);
				System.out.println(returnType2);
			}
			methodsFound++;
			buf.append('\t');
			appendAccess(buf, method.access & methodAccessMask);
			appendReturnType(buf, returnType, typeAnnotations);
			buf.append(method.name.replace("<init>", currentClass));
			buf.append('(');
			for(int i = 0, n = argumentTypes.size(); i < n; i++) {
				if(i != 0) {
					buf.append(", ");
				}
				appendArgumentType(buf, argumentTypes.get(i), i, typeAnnotations);
				buf.append("arg" + i);
			}
			appendMethodEnd(cn, buf, method, returnType);
		}
		return methodsFound;
	}
	private static void appendMethodEnd(ClassNode cn, StringBuilder buf, MethodNode method, Type returnType) {
		if((cn.access & Opcodes.ACC_INTERFACE) != 0) {
			buf.append(");\n");
		} else if((method.access & Opcodes.ACC_ABSTRACT) != 0) {
			buf.append(");\n");
		} else {
			switch(returnType.getSort()) {
				case Type.VOID:
					buf.append(") {}\n");
					break;
				case Type.BOOLEAN:
					buf.append(") {return false;}\n");
					break;
				case Type.ARRAY:
				case Type.OBJECT:
					buf.append(") {return null;}\n");
					break;
				default:
					buf.append(") {return 0;}\n");
			}
		}
	}
	private static void output(String name, String data) throws IOException {
		output(name, data, "src");
	}
	private static void outputExtra(String name, String data) throws IOException {
		output(name, data, "src-extra");
	}
	private static void output(String name, String data, String src) throws IOException {
		Path path = Paths.get(TEST_PROJECT_PATH, src, name.replace(".class", ".java"));
		byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
		System.out.println(name + "\n" + data);
	}
	private static boolean keepNullable(AnnotationNode anno) {
		return Type.getType(anno.desc).getClassName().contains("Nullable") == false;
	}
	private static List<AnnotationNode> collect(java.util.List<AnnotationNode>[] items) {
		return asList(items).stream().map(List::fromJavaCollection).flatMap(List::stream).toList().toList();
	}
	private static void appendArgumentType(
		StringBuilder out,
		Type argumentType,
		int index,
		List<TypeAnnotationNode> typeAnnotations) {
		String className = null;
		for(TypeAnnotationNode node : typeAnnotations) {
			TypeReference reference = new TypeReference(node.typeRef);
			if(reference.getSort() != TypeReference.METHOD_FORMAL_PARAMETER || node.desc.contains("Nullable") == false) {
				continue;
			}
			if(reference.getFormalParameterIndex() != index) {
				continue;
			}
			className = adjustTypeSimplistically(argumentType, node);
			break;
		}
		if(className == null) {
			className = argumentType.getClassName();
		}
		out.append(className).append(' ');
	}
	private static void appendReturnType(StringBuilder out, Type returnType, List<TypeAnnotationNode> typeAnnotations) {
		String className = null;
		for(TypeAnnotationNode node : typeAnnotations) {
			TypeReference reference = new TypeReference(node.typeRef);
			if(reference.getSort() != TypeReference.METHOD_RETURN || node.desc.contains("Nullable") == false) {
				continue;
			}
			className = adjustTypeSimplistically(returnType, node);
			break;
		}
		if(className == null) {
			className = returnType.getClassName();
		}
		out.append(className).append(' ');
	}
	private static String adjustTypeSimplistically(Type type, TypeAnnotationNode node) {
		int indexOf;
		String desc = type.getDescriptor();
		if(node.typePath != null) {
			indexOf = 0;
			TypePath path = node.typePath;
			for(int i = 0, n = path.getLength(); i < n; i++) {
				if(indexOf != -1) {
					switch(path.getStep(i)) {
						case TypePath.ARRAY_ELEMENT:
							indexOf = desc.indexOf('[', indexOf);
							break;
						case TypePath.INNER_TYPE:
							indexOf = desc.indexOf('.', indexOf);
							break;
						case TypePath.TYPE_ARGUMENT:
							// Needed to handle things like Class<@Nullable ?>
							// Nothing for now
							break;
						case TypePath.WILDCARD_BOUND:
							indexOf = desc.indexOf('*', indexOf);
							break;
					}
				}
			}
			if(indexOf != -1) {
				indexOf = desc.indexOf(';', indexOf);
				if(indexOf != -1) {
					indexOf = desc.lastIndexOf('/', indexOf);
				}
			}
		} else {
			indexOf = desc.lastIndexOf('/');
		}
		if(indexOf != -1) {
			String descriptor = desc.substring(0, indexOf + 1) + "@Nullable " + desc.substring(indexOf + 1);
			return Type.getReturnType(descriptor).getClassName();
		}
		String className = type.getClassName();
		if(desc.contains("[")) {
			return className.substring(0, className.length() - 2) + " @Nullable []";
		}
		return className;
	}
	private static <T> List<T> asList(java.util.List<T> items) {
		return items == null || items.size() == 0 ? List.of() : List.fromJavaCollection(items);
	}
	private static <T> List<T> asList(T[] items) {
		return items == null || items.length == 0 ? List.of() : List.of(items);
	}
	private static void appendAccess(StringBuilder buf, int access) {
		if((access & Opcodes.ACC_PUBLIC) != 0) {
			buf.append("public ");
		}
		if((access & Opcodes.ACC_PRIVATE) != 0) {
			buf.append("private ");
		}
		if((access & Opcodes.ACC_PROTECTED) != 0) {
			buf.append("protected ");
		}
		if((access & Opcodes.ACC_FINAL) != 0) {
			buf.append("final ");
		}
		if((access & Opcodes.ACC_STATIC) != 0) {
			buf.append("static ");
		}
		if((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
			buf.append("synchronized ");
		}
		if((access & Opcodes.ACC_VOLATILE) != 0) {
			buf.append("volatile ");
		}
		if((access & Opcodes.ACC_TRANSIENT) != 0) {
			buf.append("transient ");
		}
		if((access & Opcodes.ACC_ABSTRACT) != 0) {
			buf.append("abstract ");
		}
		if((access & Opcodes.ACC_STRICT) != 0) {
			buf.append("strictfp ");
		}
		if((access & Opcodes.ACC_SYNTHETIC) != 0) {
			buf.append("synthetic ");
		}
		if((access & Opcodes.ACC_MANDATED) != 0) {
			buf.append("mandated ");
		}
		if((access & Opcodes.ACC_ENUM) != 0) {
			buf.append("enum ");
		}
	}
	private static String name(@SuppressWarnings("unused") JarFile jar, JarEntry entry) {
		return entry.getName();
	}
	private static boolean filter(JarEntry entry) {
		return entry.isDirectory() == false;
	}
	private static byte[] readFully(JarFile jar, JarEntry entry) throws IOException {
		try(InputStream inputStream = jar.getInputStream(entry);) {
			ByteArrayOutputStream bout = new ByteArrayOutputStream(10240);
			byte[] array = new byte[10240];
			int count;
			while((count = inputStream.read(array)) > 0) {
				bout.write(array, 0, count);
			}
			return bout.toByteArray();
		}
	}
}
