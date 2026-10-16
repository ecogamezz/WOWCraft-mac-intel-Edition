import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Makes WowCraft's Minecraft mod run on macOS: its {@code SkyLink} reaches the WoW client through
 * kernel32 (a named file mapping, a named mutex, Windows' clocks), and this points those calls at
 * {@code MacNative} instead. Only {@code SkyLink}'s static initializer changes:
 *
 * <ul>
 *   <li>{@code SymbolLookup.libraryLookup("kernel32", arena)} becomes {@code MacNative.libraryLookup};</li>
 *   <li>{@code linker.downcallHandle(address, descriptor, options)} becomes
 *       {@code MacNative.downcallHandle(linker, address, descriptor, options)};</li>
 *   <li>the call-state name {@code "GetLastError"} becomes {@code "errno"}, the one macOS has.</li>
 * </ul>
 *
 * Every call site keeps its exact {@code invokeExact} type, and the rest of the mod is untouched.
 *
 * <pre>java MacPatch.java in.jar out.jar classes-dir</pre>
 * where {@code classes-dir} holds the compiled {@code dev/skycraft/link/MacNative*.class}.
 */
public final class MacPatch {
	private static final String SKYLINK = "dev/skycraft/link/SkyLink.class";
	private static final ClassDesc SYMBOL_LOOKUP = ClassDesc.of("java.lang.foreign.SymbolLookup");
	private static final ClassDesc ARENA = ClassDesc.of("java.lang.foreign.Arena");
	private static final ClassDesc LINKER = ClassDesc.of("java.lang.foreign.Linker");
	private static final ClassDesc MEMORY_SEGMENT = ClassDesc.of("java.lang.foreign.MemorySegment");
	private static final ClassDesc FUNCTION_DESCRIPTOR = ClassDesc.of("java.lang.foreign.FunctionDescriptor");
	private static final ClassDesc OPTION_ARRAY = ClassDesc.of("java.lang.foreign.Linker$Option").arrayType();
	private static final ClassDesc METHOD_HANDLE = ClassDesc.of("java.lang.invoke.MethodHandle");
	private static final ClassDesc MAC_NATIVE = ClassDesc.of("dev.skycraft.link.MacNative");

	private static final MethodTypeDesc LIBRARY_LOOKUP = MethodTypeDesc.of(SYMBOL_LOOKUP, ConstantDescs.CD_String, ARENA);
	private static final MethodTypeDesc DOWNCALL = MethodTypeDesc.of(METHOD_HANDLE, MEMORY_SEGMENT, FUNCTION_DESCRIPTOR, OPTION_ARRAY);

	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			System.err.println("usage: java MacPatch.java in.jar out.jar classes-dir");
			System.exit(2);
		}
		Path in = Path.of(args[0]), out = Path.of(args[1]), classes = Path.of(args[2]);
		List<Path> ours = new ArrayList<>();
		try (var walk = Files.walk(classes.resolve("dev/skycraft/link"))) {
			walk.filter(p -> p.getFileName().toString().startsWith("MacNative") && p.toString().endsWith(".class")).forEach(ours::add);
		}
		if (ours.isEmpty()) {
			throw new IllegalStateException("no MacNative classes in " + classes);
		}
		try (ZipFile zip = new ZipFile(in.toFile());
			 ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out));
			 URLClassLoader jarLoader = new URLClassLoader(new URL[] {in.toUri().toURL()}, null)) {
			boolean patched = false;
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry e = entries.nextElement();
				byte[] bytes = zip.getInputStream(e).readAllBytes();
				if (e.getName().equals(SKYLINK)) {
					bytes = patch(bytes, jarLoader);
					patched = true;
				} else if (e.getName().equals("fabric.mod.json")) {
					bytes = markMac(bytes);
				} else if (e.getName().startsWith("dev/skycraft/link/MacNative")) {
					continue;  // an older patch's; ours goes in below
				}
				ZipEntry copy = new ZipEntry(e.getName());
				copy.setTime(e.getTime());
				zout.putNextEntry(copy);
				zout.write(bytes);
				zout.closeEntry();
			}
			if (!patched) {
				throw new IllegalStateException(SKYLINK + " not in " + in);
			}
			for (Path p : ours) {
				zout.putNextEntry(new ZipEntry(classes.relativize(p).toString().replace('\\', '/')));
				zout.write(Files.readAllBytes(p));
				zout.closeEntry();
			}
		}
		System.out.println("patched " + in.getFileName() + " -> " + out.getFileName());
	}

	/** SkyLink with its static initializer's kernel32 calls pointed at MacNative. */
	static byte[] patch(byte[] bytes, ClassLoader jarLoader) {
		ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
			ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(jarLoader))));
		ClassModel model = cf.parse(bytes);
		int[] lookups = {0}, downcalls = {0}, names = {0};
		CodeTransform code = (b, element) -> {
			if (element instanceof InvokeInstruction inv) {
				if (inv.opcode() == Opcode.INVOKESTATIC && inv.owner().asSymbol().equals(SYMBOL_LOOKUP)
					&& inv.name().equalsString("libraryLookup") && inv.typeSymbol().equals(LIBRARY_LOOKUP)) {
					b.invokestatic(MAC_NATIVE, "libraryLookup", LIBRARY_LOOKUP);
					lookups[0]++;
					return;
				}
				if (inv.opcode() == Opcode.INVOKEINTERFACE && inv.owner().asSymbol().equals(LINKER)
					&& inv.name().equalsString("downcallHandle") && inv.typeSymbol().equals(DOWNCALL)) {
					// The receiver (the linker) stays on the stack as the first argument.
					b.invokestatic(MAC_NATIVE, "downcallHandle", DOWNCALL.insertParameterTypes(0, LINKER));
					downcalls[0]++;
					return;
				}
			}
			if (element instanceof ConstantInstruction.LoadConstantInstruction ldc && "GetLastError".equals(ldc.constantValue())) {
				b.ldc("errno");
				names[0]++;
				return;
			}
			b.with(element);
		};
		byte[] out = cf.transformClass(model, ClassTransform.transformingMethods(
			m -> m.methodName().equalsString("<clinit>"), MethodTransform.transformingCode(code)));
		// What WowCraft 0.1.2's SkyLink holds: anything else is a mod this patch wasn't made for.
		if (lookups[0] != 1 || downcalls[0] != 7 || names[0] != 2) {
			throw new IllegalStateException("SkyLink isn't the one this patch knows: " + lookups[0] + " lookups, "
				+ downcalls[0] + " downcalls, " + names[0] + " GetLastError names (want 1, 7, 2)");
		}
		return out;
	}

	/** The mod's version carries "+mac", so a log says which build it is. */
	static byte[] markMac(byte[] json) {
		String text = new String(json, StandardCharsets.UTF_8);
		String patched = text.replaceFirst("(\"version\"\\s*:\\s*\"[^\"+]*)\"", "$1+mac\"");
		return patched.getBytes(StandardCharsets.UTF_8);
	}
}
