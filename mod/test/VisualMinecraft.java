import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GroundedMinecraft plus what Minecraft draws: once standing, it sends a two-tile block atlas
 * (grass, dirt), a section with a little platform and a pillar of blocks a few steps ahead, and
 * every frame an overlay (a crosshair, a hotbar, hearts) through the patched mod's own
 * writeRender and publishOverlay. A screenshot of the game then shows whether they arrive.
 *
 *   java ... VisualMinecraft.java <total seconds>
 */
public final class VisualMinecraft {
	static ByteBuffer le(int n) {
		return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
	}

	static void tile(int[] px, int w, int x0, int c1, int c2) {
		for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
			px[y * w + x0 + x] = ((x * 7 + y * 13) % 5 == 0) ? c2 : c1;
		}
	}

	static boolean sendAtlas() {
		int w = 32, h = 16;
		int[] px = new int[w * h];
		tile(px, w, 0, 0xFF3CAA5A, 0xFF2E8A44);   // grass (ABGR little-endian: R low)
		tile(px, w, 16, 0xFF43608A, 0xFF384E70);  // dirt
		ByteBuffer body = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int p : px) body.putInt(p);
		return SkyLink.writeRender(Proto.REN_ATLAS, le(8).putInt(w).putInt(h).flip(), body.flip());
	}

	/** One cube's 36 vertices at block (bx, by, bz) inside its section: grass on top, dirt round. */
	static void cube(ByteBuffer out, int bx, int by, int bz) {
		float[][][] faces = {
			{{0,0,0},{1,0,0},{1,0,1},{0,0,1}},   // down 0
			{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},   // up 1
			{{0,0,0},{0,1,0},{1,1,0},{1,0,0}},   // north 2
			{{0,0,1},{1,0,1},{1,1,1},{0,1,1}},   // south 3
			{{0,0,0},{0,0,1},{0,1,1},{0,1,0}},   // west 4
			{{1,0,0},{1,1,0},{1,1,1},{1,0,1}},   // east 5
		};
		for (int d = 0; d < 6; d++) {
			float u0 = d == 1 ? 0f : 0.5f, u1 = u0 + 0.5f;
			float[][] uv = {{u0, 1}, {u0, 0}, {u1, 0}, {u1, 1}};
			int[] order = {0, 1, 2, 0, 2, 3};
			for (int i : order) {
				float[] c = faces[d][i];
				out.putFloat(bx + c[0]).putFloat(by + c[1]).putFloat(bz + c[2]);
				out.putFloat(uv[i][0]).putFloat(uv[i][1]);
				out.putInt(-1);            // white
				out.putInt(15 << 8);       // full sky light
				out.putInt((d + 1) << 4);  // its face
			}
		}
	}

	static boolean sendBlocks(double x, double y, double z, double yaw) {
		// Three blocks ahead along the view, on the ground.
		double r = Math.toRadians(yaw);
		int cx = (int) Math.floor(x - Math.sin(r) * 4), cy = (int) Math.floor(y), cz = (int) Math.floor(z + Math.cos(r) * 4);
		int sx = Math.floorDiv(cx, 16), sy = Math.floorDiv(cy, 16), sz = Math.floorDiv(cz, 16);
		int[][] blocks = new int[12][];
		int n = 0;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) blocks[n++] = new int[] {cx + dx, cy, cz + dz};
		for (int dy = 1; dy <= 3; dy++) blocks[n++] = new int[] {cx, cy + dy, cz};
		ByteBuffer body = ByteBuffer.allocateDirect(n * 36 * 32).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < n; i++) {
			int[] b = blocks[i];
			// Keep to one section for the test.
			cube(body, Math.floorMod(b[0], 16), Math.floorMod(b[1], 16), Math.floorMod(b[2], 16));
		}
		System.out.printf("visual minecraft: blocks at %d %d %d (section %d %d %d)%n", cx, cy, cz, sx, sy, sz);
		return SkyLink.writeRender(Proto.REN_SECTION, le(16).putInt(sx).putInt(sy).putInt(sz).putInt(n * 36).flip(), body.flip());
	}

	static void publishOverlay(int w, int h) {
		MemorySegment s = SkyLink.segment();
		if (s == null || w <= 0 || h <= 0) return;
		long base = SkyLink.overlayBackSlotOffset();
		// Bottom-up rows, as the mod reads Minecraft's frame back; transparent but for the HUD.
		MemorySegment.copy(new byte[w * h * 4], 0, s, ValueLayout.JAVA_BYTE, base, w * h * 4);
		int unit = Math.max(2, h / 270);
		java.util.function.IntBinaryOperator at = (x, yTop) -> 0;
		for (int y = 0; y < h; y++) {
			int yTop = h - 1 - y;
			for (int x = 0; x < w; x++) {
				int c = 0;
				// crosshair
				if ((Math.abs(x - w / 2) <= unit * 5 && Math.abs(yTop - h / 2) <= unit / 2) || (Math.abs(yTop - h / 2) <= unit * 5 && Math.abs(x - w / 2) <= unit / 2)) c = 0xFFFFFFFF;
				// hotbar: nine slots
				int barW = 182 * unit, barH = 22 * unit, bx = (w - barW) / 2, by = h - barH - unit * 2;
				if (x >= bx && x < bx + barW && yTop >= by && yTop < by + barH) {
					int slot = (x - bx) / (20 * unit);
					boolean edge = (x - bx) % (20 * unit) < unit || yTop - by < unit || by + barH - yTop <= unit;
					c = edge ? 0xFF606060 : (slot == 0 ? 0xFF2A9DF4 : 0xB0202020);
				}
				// hearts: ten red squares over the bar's left
				int hy = by - 11 * unit;
				if (yTop >= hy && yTop < hy + 8 * unit && x >= bx && x < bx + 80 * unit && (x - bx) % (8 * unit) < 7 * unit) c = 0xFF2020E0;
				if (c != 0) s.set(ValueLayout.JAVA_INT, base + ((long) y * w + x) * 4, c);
			}
		}
		SkyLink.publishOverlay(w, h, true, System.nanoTime());
	}

	public static void main(String[] args) throws Exception {
		long total = Long.parseLong(args.length > 0 ? args[0] : "70") * 1000;
		long start = System.currentTimeMillis();
		var sky = new SkyLink.SkyState();
		var mc = new SkyLink.McState();
		int lastSeq = -1, ack = -1;
		double[] cur = null, prev = null;
		double vy = 0;
		boolean onGround = false, sent = false;
		long nextTick = 0, nextOverlay = 0;
		SkyLink.announceRunning();
		while (System.currentTimeMillis() - start < total) {
			SkyLink.poll();
			GroundedMinecraft.drain();
			long now = System.currentTimeMillis();
			if (SkyLink.active() && SkyLink.readSkyState(sky) && sky.inGame()) {
				if (sky.teleportSeq != lastSeq) {
					lastSeq = sky.teleportSeq; ack = sky.teleportSeq;
					cur = new double[] {sky.x, sky.y, sky.z}; prev = cur.clone(); vy = 0;
				}
				int fx = (int) Math.floor(cur[0]), fy = (int) Math.floor(cur[1]), fz = (int) Math.floor(cur[2]);
				boolean known = GroundedMinecraft.KNOWN.contains(GroundedMinecraft.key(Math.floorDiv(fx, 8), Math.floorDiv(fy - 1, 8), Math.floorDiv(fz, 8)));
				if (now >= nextTick) {
					nextTick = now + 50;
					prev = cur.clone();
					if (known && !sky.loading()) {
						double g = GroundedMinecraft.ground(cur[0], cur[1], cur[2], 0.6);
						vy = (vy - 0.08) * 0.98;
						double next = cur[1] + vy;
						if (g > Double.NEGATIVE_INFINITY && next <= g) { cur[1] = g; vy = 0; onGround = true; } else { cur[1] = next; onGround = false; }
					}
					mc.tickQpc = SkyLink.qpc(); mc.tickMs = 50f;
					mc.prevX = prev[0]; mc.prevY = prev[1]; mc.prevZ = prev[2];
					mc.curX = cur[0]; mc.curY = cur[1]; mc.curZ = cur[2];
					mc.eyeHeightO = 1.62f; mc.eyeHeightT = 1.62f;
				}
				if (onGround && !sent) {
					sent = sendAtlas() && sendBlocks(cur[0], cur[1], cur[2], sky.yaw);
				}
				if (onGround && now >= nextOverlay) {
					nextOverlay = now + 100;
					publishOverlay(sky.viewportW, sky.viewportH);
				}
				mc.flags = Proto.MC_IN_WORLD | (onGround ? Proto.MC_ON_GROUND : 0);
				mc.x = cur[0]; mc.y = cur[1]; mc.z = cur[2];
				mc.yaw = sky.yaw; mc.pitch = sky.pitch; mc.eyeHeight = 1.62f;
				mc.eyeX = cur[0]; mc.eyeY = cur[1] + 1.62; mc.eyeZ = cur[2];
				mc.fov = 70f; mc.teleportAck = known ? ack : ack - 1;
				mc.health = 20f; mc.maxHealth = 20f; mc.mouseSensitivity = 0.5f;
				mc.frameCounter++;
				SkyLink.writeMcState(mc);
			}
			Thread.sleep(8);
		}
	}
}
