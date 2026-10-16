import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;

/**
 * Minecraft's side alone, through the patched mod's real SkyLink: links to whatever game made the
 * shared memory (the WoW client, or wowcraft-link's fake_game), then for a few seconds reports a
 * walk and its hearts, reads the game's state and input, and sends one hit. Exits non-zero if it
 * never linked or never saw the game's frames advance.
 */
public final class FakeMinecraft {
	public static void main(String[] args) throws Exception {
		long until = System.currentTimeMillis() + Long.parseLong(args.length > 0 ? args[0] : "6") * 1000;
		var sky = new SkyLink.SkyState();
		var mc = new SkyLink.McState();
		int keys = 0, frames = 0, firstSeq = -1, lastSeq = -1;
		boolean hit = false;
		SkyLink.announceRunning();
		while (System.currentTimeMillis() < until) {
			SkyLink.poll();
			if (SkyLink.active() && SkyLink.readSkyState(sky)) {
				if (firstSeq < 0) {
					firstSeq = sky.seq;
					System.out.printf("fake minecraft: linked to game pid %d at %.2f %.2f %.2f, viewport %dx%d%n",
						SkyLink.skyrimPid(), sky.x, sky.y, sky.z, sky.viewportW, sky.viewportH);
				}
				lastSeq = sky.seq;
				int[] got = {0};
				SkyLink.drainInput((type, code, a, b, c) -> { if (type == Proto.IN_KEY) got[0]++; });
				keys += got[0];
				mc.flags = Proto.MC_IN_WORLD | Proto.MC_ON_GROUND;
				mc.x = sky.x + frames * 0.01; mc.y = sky.y; mc.z = sky.z;
				mc.yaw = sky.yaw; mc.health = 19.5f; mc.maxHealth = 20f;
				mc.frameCounter = ++frames;
				SkyLink.writeMcState(mc);
				if (!hit && frames > 30) {
					SkyLink.pushEvent(Proto.EV_HIT_ACTOR, 1234, 7.0f, 0, 0, 0.4f, Proto.HIT_CRITICAL);
					hit = true;
				}
			}
			Thread.sleep(16);
		}
		System.out.printf("fake minecraft: %d frames written, game seq %d -> %d, %d key presses received%n", frames, firstSeq, lastSeq, keys);
		if (firstSeq < 0 || lastSeq <= firstSeq) {
			System.exit(1);
		}
	}
}
