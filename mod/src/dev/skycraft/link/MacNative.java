package dev.skycraft.link;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The seven kernel32 functions {@link SkyLink} calls, for macOS. WowMacCraft's patcher points
 * SkyLink's {@code SymbolLookup.libraryLookup("kernel32", ...)} and {@code Linker.downcallHandle}
 * here, so every call site keeps its exact method type and nothing else in the mod changes.
 *
 * <ul>
 *   <li>A named file mapping ({@code Local\SkyCraft_v1}) is a POSIX shared memory object,
 *       {@code /SkyCraft_v1.<uid>}: the WoW client creates it and Minecraft opens it, as on
 *       Windows. It outlives either side (Windows' lives while a handle does), so a restarted
 *       game finds the one Minecraft still has mapped; the launcher removes it on Stop.</li>
 *   <li>A named mutex ({@code Local\SkyCraft_v1_minecraft}) is a shared {@code flock} on
 *       {@code /tmp/SkyCraft_v1_minecraft.<uid>.lock}, held until the process ends, as the
 *       handle was. Anyone asking "is Minecraft running?" tries to lock it exclusively.</li>
 *   <li>{@code GetTickCount64} and {@code QueryPerformanceCounter} read {@code CLOCK_UPTIME_RAW}
 *       (milliseconds, and nanoseconds at 10^9 a second), the clock the game side reads.</li>
 * </ul>
 *
 * <p>Loading it also tells macOS this process is doing work the player is waiting on, so App Nap
 * doesn't slow a Minecraft whose window is hidden.
 */
public final class MacNative {
	private MacNative() {}

	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup LIBC = LINKER.defaultLookup();
	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	/** The kernel32 names SkyLink looks up, in the order of their stand-ins' Java names. */
	private static final String[] NAMES = {
		"OpenFileMappingW", "MapViewOfFile", "GetTickCount64", "GetCurrentProcessId",
		"QueryPerformanceCounter", "QueryPerformanceFrequency", "CreateMutexW",
	};
	private static final String[] JAVA_NAMES = {
		"openFileMapping", "mapViewOfFile", "getTickCount64", "getCurrentProcessId",
		"queryPerformanceCounter", "queryPerformanceFrequency", "createMutex",
	};
	/** Where the stand-ins' fake addresses start: never called natively, only told apart. */
	private static final long MARKER_BASE = 0x5C00_0000L;
	/** A handle is a file descriptor plus this, so a real one is never NULL. */
	private static final long HANDLE_BASE = 0x1_0000L;

	// <sys/fcntl.h>, <sys/mman.h>, <sys/file.h>, <time.h>, <errno.h> on macOS.
	private static final int O_RDWR = 0x0002, O_CREAT = 0x0200;
	private static final int PROT_RW = 0x1 | 0x2, MAP_SHARED = 0x1;
	private static final int LOCK_SH = 1, LOCK_NB = 4;
	private static final int CLOCK_UPTIME_RAW = 8;
	private static final int ENOENT = 2, EACCES = 13;
	// What Windows said for those.
	private static final int ERROR_FILE_NOT_FOUND = 2, ERROR_ACCESS_DENIED = 5;

	private static final StructLayout ERRNO_LAYOUT = Linker.Option.captureStateLayout();
	private static final VarHandle ERRNO = ERRNO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));
	private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");

	private static final MethodHandle SHM_OPEN = libc("shm_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), CAPTURE_ERRNO, Linker.Option.firstVariadicArg(2));
	private static final MethodHandle OPEN = libc("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), CAPTURE_ERRNO, Linker.Option.firstVariadicArg(2));
	private static final MethodHandle CLOSE = libc("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
	private static final MethodHandle FLOCK = libc("flock", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
	private static final MethodHandle MMAP = libc("mmap", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
	// struct stat with 64-bit inodes (the only kind on arm64, `$INODE64` on x86_64); st_size at 96.
	private static final MethodHandle FSTAT = libc(LIBC.find("fstat$INODE64").isPresent() ? "fstat$INODE64" : "fstat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
	private static final long STAT_BYTES = 144, STAT_SIZE = 96;
	private static final MethodHandle CLOCK_NSEC = libc("clock_gettime_nsec_np", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
	private static final MethodHandle GETUID = libc("getuid", FunctionDescriptor.of(JAVA_INT));

	private static volatile boolean awake;

	private static MethodHandle libc(String name, FunctionDescriptor fd, Linker.Option... options) {
		return LINKER.downcallHandle(LIBC.find(name).orElseThrow(), fd, options);
	}

	// ---- what SkyLink's static initializer calls instead -------------------------------------

	/** {@code SymbolLookup.libraryLookup}: kernel32's names found here, any other library as before. */
	public static SymbolLookup libraryLookup(String name, Arena arena) {
		if (!name.equalsIgnoreCase("kernel32")) {
			return SymbolLookup.libraryLookup(name, arena);
		}
		keepAwake();
		return symbol -> {
			for (int i = 0; i < NAMES.length; i++) {
				if (NAMES[i].equals(symbol)) {
					return Optional.of(MemorySegment.ofAddress(MARKER_BASE + i));
				}
			}
			return Optional.empty();
		};
	}

	/**
	 * {@code Linker.downcallHandle}: one of the stand-ins, with the type the real downcall would
	 * have had (the call-state segment first when a call state is captured), else the real thing.
	 */
	public static MethodHandle downcallHandle(Linker linker, MemorySegment target, FunctionDescriptor fd, Linker.Option[] options) {
		long i = target.address() - MARKER_BASE;
		if (i < 0 || i >= NAMES.length) {
			return linker.downcallHandle(target, fd, options);
		}
		MethodType type = fd.toMethodType();
		if (options.length > 0) {
			// SkyLink passes no option but captureCallState.
			type = type.insertParameterTypes(0, MemorySegment.class);
		}
		try {
			return LOOKUP.findStatic(MacNative.class, JAVA_NAMES[(int) i], type);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("WowMacCraft: no stand-in for " + NAMES[(int) i] + type, e);
		}
	}

	// ---- the stand-ins -----------------------------------------------------------------------

	/** {@code OpenFileMappingW(access, inherit, name)}: the shared memory object, or NULL with the error. */
	static MemorySegment openFileMapping(MemorySegment state, int access, int inherit, MemorySegment name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
			int fd = (int) SHM_OPEN.invokeExact(errno, arena.allocateFrom(shmName(wide(name))), O_RDWR, 0);
			if (fd < 0) {
				int e = (int) ERRNO.get(errno, 0L);
				setLastError(state, e == ENOENT ? ERROR_FILE_NOT_FOUND : e == EACCES ? ERROR_ACCESS_DENIED : e);
				return MemorySegment.NULL;
			}
			return MemorySegment.ofAddress(HANDLE_BASE + fd);
		} catch (Throwable t) {
			setLastError(state, ERROR_ACCESS_DENIED);
			return MemorySegment.NULL;
		}
	}

	/**
	 * {@code MapViewOfFile(handle, access, offHigh, offLow, bytes)}: the whole object mapped
	 * shared (0 bytes: all of it), or NULL. The descriptor closes once mapped; the mapping stays.
	 */
	static MemorySegment mapViewOfFile(MemorySegment handle, int access, int offHigh, int offLow, long bytes) {
		int fd = (int) (handle.address() - HANDLE_BASE);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment st = arena.allocate(STAT_BYTES, 8);
			if ((int) FSTAT.invokeExact(fd, st) != 0) {
				return MemorySegment.NULL;
			}
			long size = st.get(JAVA_LONG, STAT_SIZE);
			long len = bytes != 0 ? bytes : size;
			// An object smaller than the protocol (a stale one, half made) would fault when read.
			if (len <= 0 || len > size || size < Proto.MAPPING_BYTES) {
				return MemorySegment.NULL;
			}
			MemorySegment at = (MemorySegment) MMAP.invokeExact(MemorySegment.NULL, len, PROT_RW, MAP_SHARED, fd, 0L);
			return at.address() == -1L ? MemorySegment.NULL : at;
		} catch (Throwable t) {
			return MemorySegment.NULL;
		} finally {
			close(fd);
		}
	}

	/** {@code GetTickCount64()}: milliseconds of the shared clock. */
	static long getTickCount64() {
		return nanos() / 1_000_000L;
	}

	/** {@code GetCurrentProcessId()}. */
	static int getCurrentProcessId() {
		return (int) ProcessHandle.current().pid();
	}

	/** {@code QueryPerformanceCounter(out)}: nanoseconds of the shared clock. */
	static int queryPerformanceCounter(MemorySegment out) {
		out.reinterpret(8).set(JAVA_LONG, 0, nanos());
		return 1;
	}

	/** {@code QueryPerformanceFrequency(out)}: the counter's ticks a second. */
	static int queryPerformanceFrequency(MemorySegment out) {
		out.reinterpret(8).set(JAVA_LONG, 0, 1_000_000_000L);
		return 1;
	}

	/** {@code CreateMutexW(attributes, owned, name)}: a held lock file, or NULL. */
	static MemorySegment createMutex(MemorySegment attributes, int initialOwner, MemorySegment name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
			int fd = (int) OPEN.invokeExact(errno, arena.allocateFrom(lockPath(wide(name))), O_RDWR | O_CREAT, 0600);
			if (fd < 0) {
				return MemorySegment.NULL;
			}
			if ((int) FLOCK.invokeExact(fd, LOCK_SH | LOCK_NB) != 0) {
				close(fd);
				return MemorySegment.NULL;
			}
			// Never closed: the lock goes when the process does, as a mutex handle did.
			return MemorySegment.ofAddress(HANDLE_BASE + fd);
		} catch (Throwable t) {
			return MemorySegment.NULL;
		}
	}

	// ---- names, the clock, App Nap ------------------------------------------------------------

	/** {@code Local\SkyCraft_v1} as the POSIX shared memory name {@code /SkyCraft_v1.<uid>}. */
	public static String shmName(String windowsName) {
		return "/" + baseName(windowsName) + "." + uid();
	}

	/** {@code Local\SkyCraft_v1_minecraft} as {@code /tmp/SkyCraft_v1_minecraft.<uid>.lock}. */
	public static String lockPath(String windowsName) {
		return "/tmp/" + baseName(windowsName) + "." + uid() + ".lock";
	}

	private static String baseName(String windowsName) {
		int slash = windowsName.lastIndexOf('\\');
		return windowsName.substring(slash + 1).replace('/', '_');
	}

	private static int uid() {
		try {
			return (int) GETUID.invokeExact();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** {@code CLOCK_UPTIME_RAW} in nanoseconds: what the WoW client stamps its heartbeats with too. */
	public static long nanos() {
		try {
			return (long) CLOCK_NSEC.invokeExact(CLOCK_UPTIME_RAW);
		} catch (Throwable t) {
			return System.nanoTime();
		}
	}

	/** A UTF-16 (wide) C string, as Windows' W functions take. */
	private static String wide(MemorySegment s) {
		return s.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
	}

	private static void setLastError(MemorySegment state, int error) {
		state.reinterpret(ERRNO_LAYOUT.byteSize()).set(JAVA_INT, ERRNO_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("errno")), error);
	}

	private static void close(int fd) {
		try {
			int ignored = (int) CLOSE.invokeExact(fd);
		} catch (Throwable ignored) {
		}
	}

	/**
	 * {@code [[NSProcessInfo processInfo] beginActivityWithOptions:reason:]}, kept for the life of
	 * the process: user-initiated, latency-critical work (idle sleep still allowed), which App Nap
	 * leaves alone even with no window on screen.
	 */
	private static synchronized void keepAwake() {
		if (awake) {
			return;
		}
		awake = true;
		try {
			SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
			SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", Arena.global());
			MethodHandle getClass = LINKER.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));
			MethodHandle sel = LINKER.downcallHandle(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));
			MemorySegment send = objc.find("objc_msgSend").orElseThrow();
			MethodHandle send0 = LINKER.downcallHandle(send, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
			MethodHandle sendStr = LINKER.downcallHandle(send, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
			MethodHandle sendBegin = LINKER.downcallHandle(send, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
			Arena g = Arena.global();
			MemorySegment processInfo = (MemorySegment) send0.invokeExact((MemorySegment) getClass.invokeExact(g.allocateFrom("NSProcessInfo")), (MemorySegment) sel.invokeExact(g.allocateFrom("processInfo")));
			MemorySegment reason = (MemorySegment) sendStr.invokeExact((MemorySegment) getClass.invokeExact(g.allocateFrom("NSString")), (MemorySegment) sel.invokeExact(g.allocateFrom("stringWithUTF8String:")), g.allocateFrom("WowMacCraft: Minecraft plays inside WoW"));
			// NSActivityUserInitiatedAllowingIdleSystemSleep | NSActivityLatencyCritical
			long options = 0x00EF_FFFFL | 0xFF_0000_0000L;
			MemorySegment token = (MemorySegment) sendBegin.invokeExact(processInfo, (MemorySegment) sel.invokeExact(g.allocateFrom("beginActivityWithOptions:reason:")), options, reason);
			// Retained, never ended: the activity lasts as long as the process.
			MemorySegment ignored = (MemorySegment) send0.invokeExact(token, (MemorySegment) sel.invokeExact(g.allocateFrom("retain")));
		} catch (Throwable t) {
			System.err.println("WowMacCraft: couldn't keep Minecraft out of App Nap: " + t);
		}
	}
}
