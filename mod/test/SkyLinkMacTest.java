import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.skycraft.link.MacNative;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * The patched mod's SkyLink on macOS, with this test playing the WoW client: before the shared
 * memory exists SkyLink says so (Windows error 2), once it does SkyLink links, stamps its pid and
 * heartbeat on the shared clock, and the state crosses both ways; its running lock is held.
 *
 *   java -cp stub-classes:slf4j-api.jar:slf4j-simple.jar:wowcraft-mac.jar test/SkyLinkMacTest.java
 */
public final class SkyLinkMacTest {
	static final Linker L = Linker.nativeLinker();
	static MethodHandle fn(String name, FunctionDescriptor fd, Linker.Option... o) {
		return L.downcallHandle(L.defaultLookup().find(name).orElseThrow(), fd, o);
	}
	static final MethodHandle SHM_OPEN = fn("shm_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2));
	static final MethodHandle SHM_UNLINK = fn("shm_unlink", FunctionDescriptor.of(JAVA_INT, ADDRESS));
	static final MethodHandle FTRUNCATE = fn("ftruncate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
	static final MethodHandle MMAP = fn("mmap", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
	static final MethodHandle OPEN = fn("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2));
	static final MethodHandle FLOCK = fn("flock", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

	static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
		System.out.println("ok: " + what);
	}

	public static void main(String[] args) throws Throwable {
		Arena a = Arena.global();
		String name = MacNative.shmName(Proto.MAPPING_NAME);
		check(name.startsWith("/SkyCraft_v1.") && name.length() <= 31, "the shared memory's name fits macOS: " + name);
		int ignored = (int) SHM_UNLINK.invokeExact(a.allocateFrom(name));

		// Not there yet: SkyLink waits, with Windows' "file not found".
		SkyLink.poll();
		check(!SkyLink.active(), "no link before the game makes the shared memory");
		var lastOpenError = SkyLink.class.getDeclaredField("lastOpenError");
		lastOpenError.setAccessible(true);
		check(lastOpenError.getInt(null) == 2, "error 2 (not created yet), as on Windows");

		// The game makes it, as the WoW client does.
		int fd = (int) SHM_OPEN.invokeExact(a.allocateFrom(name), 0x0002 | 0x0200, 0600);
		check(fd >= 0, "made the shared memory");
		check((int) FTRUNCATE.invokeExact(fd, Proto.MAPPING_BYTES) == 0, "sized it");
		MemorySegment game = ((MemorySegment) MMAP.invokeExact(MemorySegment.NULL, Proto.MAPPING_BYTES, 3, 1, fd, 0L)).reinterpret(Proto.MAPPING_BYTES);
		game.set(JAVA_INT, Proto.H_MAGIC, Proto.MAGIC);
		game.set(JAVA_INT, Proto.H_VERSION, Proto.VERSION);
		game.set(JAVA_INT, Proto.H_SKYRIM_PID, 4242);
		// The game's heartbeat, on the clock the WoW client stamps it with (CLOCK_UPTIME_RAW, ms).
		game.set(JAVA_LONG, Proto.H_SKYRIM_HEARTBEAT, MacNative.nanos() / 1_000_000L);

		Thread.sleep(1100);  // SkyLink tries once a second
		SkyLink.poll();
		check(SkyLink.active(), "linked");
		game.set(JAVA_LONG, Proto.H_SKYRIM_HEARTBEAT, MacNative.nanos() / 1_000_000L - 9000);
		check(!SkyLink.active(), "a game silent for 9 s counts as gone");
		game.set(JAVA_LONG, Proto.H_SKYRIM_HEARTBEAT, MacNative.nanos() / 1_000_000L);
		check(SkyLink.active(), "and back once it beats again");
		check(SkyLink.skyrimPid() == 4242, "sees the game's pid");
		check(game.get(JAVA_INT, Proto.H_MC_PID) == (int) ProcessHandle.current().pid(), "stamped Minecraft's pid");
		long beat = game.get(JAVA_LONG, Proto.H_MC_HEARTBEAT);
		long now = MacNative.nanos() / 1_000_000L;
		check(beat > 0 && Math.abs(now - beat) < 2000, "heartbeat on the shared clock (" + beat + " vs " + now + ")");
		check(SkyLink.qpcFrequency() == 1_000_000_000L, "counter at 10^9 a second");
		long q = SkyLink.qpc();
		check(Math.abs(q - MacNative.nanos()) < 1_000_000_000L, "counter on the shared clock");

		// Game -> Minecraft state.
		long ss = Proto.OFF_SKY_STATE;
		game.set(JAVA_INT, ss + Proto.SS_FLAGS, Proto.SKY_IN_GAME);
		game.set(JAVA_INT, ss + Proto.SS_WORLD_ID, 1);
		game.set(JAVA_DOUBLE, ss + Proto.SS_POS_X, -8949.95);
		game.set(JAVA_DOUBLE, ss + Proto.SS_POS_Y, 82.1);
		game.set(JAVA_DOUBLE, ss + Proto.SS_POS_Z, 132.5);
		game.set(JAVA_FLOAT, ss + Proto.SS_YAW, 90f);
		game.set(JAVA_INT, ss + Proto.SS_SEQ, 2);
		var st = new SkyLink.SkyState();
		check(SkyLink.readSkyState(st), "read the game's state");
		check(st.inGame() && st.worldId == 1 && st.x == -8949.95 && st.y == 82.1 && st.yaw == 90f, "the game's state arrived whole");

		// Minecraft -> game state.
		var mc = new SkyLink.McState();
		mc.flags = Proto.MC_IN_WORLD | Proto.MC_ON_GROUND;
		mc.x = 1.5; mc.y = 64; mc.z = -2.25; mc.yaw = 45f; mc.health = 17f; mc.maxHealth = 20f;
		SkyLink.writeMcState(mc);
		long ms = Proto.OFF_MC_STATE;
		check(game.get(JAVA_DOUBLE, ms + Proto.MS_X) == 1.5 && game.get(JAVA_DOUBLE, ms + Proto.MS_Z) == -2.25, "Minecraft's position arrived");
		check(game.get(JAVA_FLOAT, ms + Proto.MS_HEALTH) == 17f && game.get(JAVA_FLOAT, ms + Proto.MS_MAX_HEALTH) == 20f, "Minecraft's hearts arrived");
		check((game.get(JAVA_INT, ms + Proto.MS_SEQ) & 1) == 0, "its seqlock is even (written whole)");

		// Input ring: the game writes one key press, Minecraft drains it.
		long ir = Proto.OFF_INPUT_RING;
		long slot = ir + Proto.IR_DATA;
		game.set(java.lang.foreign.ValueLayout.JAVA_SHORT, slot, (short) Proto.IN_KEY);
		game.set(java.lang.foreign.ValueLayout.JAVA_SHORT, slot + 2, (short) 26);  // SDL W
		game.set(JAVA_INT, slot + 4, 1);
		game.set(JAVA_LONG, ir + Proto.IR_HEAD, 1);
		int[] got = new int[5];
		SkyLink.drainInput((type, code, x, y, z) -> { got[0] = type; got[1] = code; got[2] = x; got[4]++; });
		check(got[0] == Proto.IN_KEY && got[1] == 26 && got[2] == 1 && got[4] == 1, "a key press crossed the input ring");

		// The running-Minecraft lock: held by SkyLink, so nobody locks it alone.
		SkyLink.announceRunning();
		String lock = MacNative.lockPath(Proto.MAPPING_NAME + "_minecraft");
		int lfd = (int) OPEN.invokeExact(a.allocateFrom(lock), 0x0002, 0);
		check(lfd >= 0, "the running lock exists: " + lock);
		check((int) FLOCK.invokeExact(lfd, 2 | 4) != 0, "and is held while Minecraft runs");

		ignored = (int) SHM_UNLINK.invokeExact(a.allocateFrom(name));
		System.out.println("all passed");
	}
}
