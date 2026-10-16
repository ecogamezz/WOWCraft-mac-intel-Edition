import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;

/**
 * A stand-in Minecraft for driving the real WoW client end to end, through the patched mod's
 * SkyLink: it goes where the game puts it (the teleport handshake), waits a moment, then walks
 * forward along the game's view at Minecraft's walking speed for `walk` seconds, publishing 20 Hz
 * physics ticks as the mod does (previous and current feet, stamped on the shared clock), and
 * stands still after. Prints where it is each second.
 *
 *   java ... WalkingMinecraft.java <total seconds> <walk seconds>
 */
public final class WalkingMinecraft {
	public static void main(String[] args) throws Exception {
		long total = Long.parseLong(args.length > 0 ? args[0] : "40") * 1000;
		long walkMs = Long.parseLong(args.length > 1 ? args[1] : "8") * 1000;
		long start = System.currentTimeMillis();
		var sky = new SkyLink.SkyState();
		var mc = new SkyLink.McState();
		int lastSeq = -1, ack = -1;
		double[] cur = null, prev = null;
		long arrivedAt = 0, nextTick = 0, nextPrint = 0;
		final double speed = 4.317; // blocks a second, Minecraft's walk
		SkyLink.announceRunning();
		while (System.currentTimeMillis() - start < total) {
			SkyLink.poll();
			long now = System.currentTimeMillis();
			if (SkyLink.active() && SkyLink.readSkyState(sky) && sky.inGame()) {
				if (sky.teleportSeq != lastSeq) {
					lastSeq = sky.teleportSeq;
					cur = new double[] {sky.x, sky.y, sky.z};
					prev = cur.clone();
					ack = sky.teleportSeq;
					arrivedAt = now;
					System.out.printf("walking minecraft: put at %.2f %.2f %.2f (teleport %d)%n", sky.x, sky.y, sky.z, ack);
				}
				if (now >= nextTick) {
					nextTick = now + 50;
					prev = cur.clone();
					boolean walking = now - arrivedAt > 2000 && now - arrivedAt < 2000 + walkMs;
					if (walking) {
						double yaw = Math.toRadians(sky.yaw);
						cur[0] += -Math.sin(yaw) * speed * 0.05;
						cur[2] += Math.cos(yaw) * speed * 0.05;
					}
					mc.tickQpc = SkyLink.qpc();
					mc.tickMs = 50f;
					mc.prevX = prev[0]; mc.prevY = prev[1]; mc.prevZ = prev[2];
					mc.curX = cur[0]; mc.curY = cur[1]; mc.curZ = cur[2];
					mc.eyeHeightO = 1.62f; mc.eyeHeightT = 1.62f;
				}
				mc.flags = Proto.MC_IN_WORLD | Proto.MC_ON_GROUND;
				mc.x = cur[0]; mc.y = cur[1]; mc.z = cur[2];
				mc.yaw = sky.yaw; mc.pitch = sky.pitch;
				mc.eyeHeight = 1.62f; mc.eyeX = cur[0]; mc.eyeY = cur[1] + 1.62; mc.eyeZ = cur[2];
				mc.fov = 70f; mc.cameraMode = 0;
				mc.teleportAck = ack;
				mc.health = 20f; mc.maxHealth = 20f; mc.mouseSensitivity = 0.5f;
				mc.frameCounter++;
				SkyLink.writeMcState(mc);
				if (now >= nextPrint) {
					nextPrint = now + 1000;
					System.out.printf("walking minecraft: at %.2f %.2f %.2f; the game says it's at %.2f %.2f %.2f, yaw %.1f, flags %#x%n",
						cur[0], cur[1], cur[2], sky.x, sky.y, sky.z, sky.yaw, sky.flags);
				}
			}
			Thread.sleep(8);
		}
	}
}
