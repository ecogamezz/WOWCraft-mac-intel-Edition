import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stand-in Minecraft that stands on the game's collision: it reads the collision ring through
 * the patched mod's SkyLink exactly as the mod's SkyCollision consumer does (clear, a region's
 * triangles, then its voxels), keeps each region's triangles, and walks forward along the game's
 * view with gravity on them. It reports what arrived and the ground under its feet, so a run says
 * whether WoW's land reached Minecraft and holds it up.
 *
 *   java ... GroundedMinecraft.java <total seconds> <walk seconds>
 */
public final class GroundedMinecraft {
	static final Map<Long, float[]> TRIS = new HashMap<>();
	static final Set<Long> KNOWN = new HashSet<>();
	static int epoch = -1, clears, regionMsgs, triMsgs;
	static long blocks;

	static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}

	static void drain() {
		MemorySegment s = SkyLink.segment();
		if (s == null) return;
		long head = SkyLink.collisionHead(), tail = SkyLink.collisionTail();
		long data = Proto.OFF_COLLISION_RING + Proto.CR_DATA, size = Proto.CR_DATA_BYTES;
		while (tail < head) {
			long pos = tail % size;
			int type = s.get(ValueLayout.JAVA_INT, data + pos);
			int bytes = s.get(ValueLayout.JAVA_INT, data + pos + 4);
			if (type == Proto.COL_PAD) { tail += size - pos; continue; }
			long p = data + pos + 8;
			if (type == Proto.COL_CLEAR) {
				epoch = s.get(ValueLayout.JAVA_INT, p); clears++; TRIS.clear(); KNOWN.clear();
			} else if (type == Proto.COL_TRIS || type == Proto.COL_REGION) {
				int minX = s.get(ValueLayout.JAVA_INT, p), minY = s.get(ValueLayout.JAVA_INT, p + 4), minZ = s.get(ValueLayout.JAVA_INT, p + 8);
				int count = s.get(ValueLayout.JAVA_INT, p + 28);
				long region = key(Math.floorDiv(minX, 8), Math.floorDiv(minY, 8), Math.floorDiv(minZ, 8));
				if (type == Proto.COL_TRIS) {
					float[] v = new float[count * 10];
					for (int i = 0; i < count; i++) {
						for (int k = 0; k < 9; k++) v[i * 10 + k] = s.get(ValueLayout.JAVA_FLOAT, p + 32 + i * 40L + k * 4L);
						v[i * 10 + 9] = Float.intBitsToFloat(s.get(ValueLayout.JAVA_INT, p + 32 + i * 40L + 36));
					}
					TRIS.put(region, v); triMsgs++;
				} else {
					KNOWN.add(region); regionMsgs++; blocks += count;
				}
			}
			tail += (8 + bytes + 7) & ~7L;
		}
		SkyLink.setCollisionTail(tail);
	}

	/** The highest walkable surface under (x, z) at or below y + step, as the mod's floor test. */
	static double ground(double x, double y, double z, double step) {
		double best = Double.NEGATIVE_INFINITY;
		int rx = Math.floorDiv((int) Math.floor(x), 8), rz = Math.floorDiv((int) Math.floor(z), 8);
		int ry0 = Math.floorDiv((int) Math.floor(y - 64), 8), ry1 = Math.floorDiv((int) Math.floor(y + step), 8);
		for (int ry = ry0; ry <= ry1; ry++) {
			float[] v = TRIS.get(key(rx, ry, rz));
			if (v == null) continue;
			for (int i = 0; i < v.length; i += 10) {
				double ax = v[i], ay = v[i + 1], az = v[i + 2], bx = v[i + 3], by = v[i + 4], bz = v[i + 5], cx = v[i + 6], cy = v[i + 7], cz = v[i + 8];
				double d = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
				if (Math.abs(d) < 1e-9) continue;
				double w0 = ((bz - cz) * (x - cx) + (cx - bx) * (z - cz)) / d;
				double w1 = ((cz - az) * (x - cx) + (ax - cx) * (z - cz)) / d;
				double w2 = 1 - w0 - w1;
				if (w0 < -1e-4 || w1 < -1e-4 || w2 < -1e-4) continue;
				double h = w0 * ay + w1 * by + w2 * cy;
				if (h <= y + step && h > best) best = h;
			}
		}
		return best;
	}

	public static void main(String[] args) throws Exception {
		long total = Long.parseLong(args.length > 0 ? args[0] : "60") * 1000;
		long walkMs = Long.parseLong(args.length > 1 ? args[1] : "10") * 1000;
		long start = System.currentTimeMillis();
		var sky = new SkyLink.SkyState();
		var mc = new SkyLink.McState();
		int lastSeq = -1, ack = -1;
		double[] cur = null, prev = null;
		double vy = 0;
		boolean onGround = false;
		long arrivedAt = 0, nextTick = 0, nextPrint = 0;
		SkyLink.announceRunning();
		while (System.currentTimeMillis() - start < total) {
			SkyLink.poll();
			drain();
			long now = System.currentTimeMillis();
			if (SkyLink.active() && SkyLink.readSkyState(sky) && sky.inGame()) {
				if (sky.teleportSeq != lastSeq) {
					lastSeq = sky.teleportSeq;
					cur = new double[] {sky.x, sky.y, sky.z};
					prev = cur.clone();
					ack = sky.teleportSeq;
					arrivedAt = now;
					vy = 0;
					System.out.printf("grounded minecraft: put at %.2f %.2f %.2f (teleport %d)%n", sky.x, sky.y, sky.z, ack);
				}
				// Held until the region under the feet is known, as the mod's holdUntilReady.
				int fx = (int) Math.floor(cur[0]), fy = (int) Math.floor(cur[1]), fz = (int) Math.floor(cur[2]);
				boolean known = KNOWN.contains(key(Math.floorDiv(fx, 8), Math.floorDiv(fy - 1, 8), Math.floorDiv(fz, 8)));
				if (now >= nextTick) {
					nextTick = now + 50;
					prev = cur.clone();
					if (known && !sky.loading()) {
						boolean walking = now - arrivedAt > 3000 && now - arrivedAt < 3000 + walkMs;
						if (walking) {
							double yaw = Math.toRadians(sky.yaw);
							cur[0] += -Math.sin(yaw) * 4.317 * 0.05;
							cur[2] += Math.cos(yaw) * 4.317 * 0.05;
						}
						double g = ground(cur[0], cur[1], cur[2], 0.6);
						vy = (vy - 0.08) * 0.98;
						double next = cur[1] + vy;
						if (g > Double.NEGATIVE_INFINITY && next <= g) { cur[1] = g; vy = 0; onGround = true; } else { cur[1] = next; onGround = false; }
					}
					mc.tickQpc = SkyLink.qpc(); mc.tickMs = 50f;
					mc.prevX = prev[0]; mc.prevY = prev[1]; mc.prevZ = prev[2];
					mc.curX = cur[0]; mc.curY = cur[1]; mc.curZ = cur[2];
					mc.eyeHeightO = 1.62f; mc.eyeHeightT = 1.62f;
				}
				mc.flags = Proto.MC_IN_WORLD | (onGround ? Proto.MC_ON_GROUND : 0);
				mc.x = cur[0]; mc.y = cur[1]; mc.z = cur[2];
				mc.yaw = sky.yaw; mc.pitch = sky.pitch; mc.eyeHeight = 1.62f;
				mc.eyeX = cur[0]; mc.eyeY = cur[1] + 1.62; mc.eyeZ = cur[2];
				mc.fov = 70f; mc.teleportAck = known ? ack : ack - 1;
				mc.health = 20f; mc.maxHealth = 20f; mc.mouseSensitivity = 0.5f;
				mc.frameCounter++;
				SkyLink.writeMcState(mc);
				if (now >= nextPrint) {
					nextPrint = now + 1000;
					int tris = TRIS.values().stream().mapToInt(v -> v.length / 10).sum();
					System.out.printf("grounded minecraft: at %.2f %.2f %.2f %s; ground %.2f; epoch %d, %d regions known, %d with triangles (%d), %d voxel blocks; game at %.2f %.2f %.2f%n",
						cur[0], cur[1], cur[2], onGround ? "on the ground" : "in the air", ground(cur[0], cur[1] + 1, cur[2], 0.0), epoch, KNOWN.size(), TRIS.size(), tris, blocks, sky.x, sky.y, sky.z);
				}
			}
			Thread.sleep(8);
		}
	}
}
