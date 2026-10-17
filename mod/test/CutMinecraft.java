import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * VisualMinecraft's stand-in, testing what the client does with Minecraft's cells, its skin and
 * its players: once standing it sends the block atlas, a 3 x 3 hole two deep dug out of WoW's
 * ground a few steps ahead (REN_DUG), a block set into the ground beside it (its section and
 * REN_CUT for its cell), our skin as our own entity texture (REN_TEXTURE, own = 1), and every
 * frame a co-op partner "Steve" walking round the hole with his shadow (WE_PLAYER, WE_SHADOW). A
 * screenshot then shows the land cut, the name tag and the shadow; the client's log says how many
 * cells it cut and that our face is the portrait.
 *
 * With a camera mode of 1 (Minecraft's view from behind, 4 blocks back) the screenshot also shows
 * whether anything of our WoW body is drawn: this stand-in sends no body of its own.
 *
 *   java ... CutMinecraft.java <total seconds> <skin.png> [camera mode]
 */
public final class CutMinecraft {
	static ByteBuffer le(int n) {
		return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** The cell a surface at height g lies in: the block it covers. */
	static int surfaceCell(double g) {
		return (int) Math.floor(g - 0.0625);
	}

	/** Cells as REN_CUT (no world) or REN_DUG (with world) messages, one per section. */
	static boolean sendCells(int type, List<int[]> cells, int world) {
		Map<Long, long[]> sections = new HashMap<>();
		Map<Long, int[]> keys = new HashMap<>();
		Map<Long, Integer> counts = new HashMap<>();
		for (int[] c : cells) {
			int sx = Math.floorDiv(c[0], 16), sy = Math.floorDiv(c[1], 16), sz = Math.floorDiv(c[2], 16);
			long k = ((long) sx << 42) ^ ((long) sy << 21) ^ (sz & 0x1FFFFF);
			long[] bits = sections.computeIfAbsent(k, x -> new long[64]);
			keys.put(k, new int[] {sx, sy, sz});
			int bit = Math.floorMod(c[0], 16) + 16 * Math.floorMod(c[2], 16) + 256 * Math.floorMod(c[1], 16);
			if ((bits[bit >> 6] & (1L << (bit & 63))) == 0) counts.merge(k, 1, Integer::sum);
			bits[bit >> 6] |= 1L << (bit & 63);
		}
		boolean ok = true;
		for (var e : sections.entrySet()) {
			int[] s = keys.get(e.getKey());
			ByteBuffer header = type == Proto.REN_DUG ? le(24) : le(16);
			header.putInt(s[0]).putInt(s[1]).putInt(s[2]).putInt(counts.get(e.getKey()));
			if (type == Proto.REN_DUG) header.putInt(world).putInt(0);
			ByteBuffer body = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);
			for (long w : e.getValue()) body.putLong(w);
			ok &= SkyLink.writeRender(type, header.flip(), body.flip());
			System.out.printf("cut minecraft: %s section %d %d %d, %d cells%n", type == Proto.REN_DUG ? "dug" : "cut", s[0], s[1], s[2], counts.get(e.getKey()));
		}
		return ok;
	}

	static boolean sendSkin(String path) throws Exception {
		BufferedImage img = ImageIO.read(new File(path));
		int w = img.getWidth(), h = img.getHeight();
		ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
			int argb = img.getRGB(x, y);
			px.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
		}
		return SkyLink.writeRender(Proto.REN_TEXTURE, le(16).putInt(900).putInt(w).putInt(h).putInt(1).flip(), px.flip());
	}

	/** A partner's name as the mod packs it: one UTF-16 unit per float, ext, uv, then scale. */
	static SkyLink.WorldEntity partner(int id, String name, double x, double y, double z) {
		float[] chars = new float[16];
		for (int i = 0; i < Math.min(16, name.length()); i++) chars[i] = name.charAt(i);
		return new SkyLink.WorldEntity(Proto.WE_PLAYER, id, (float) x, (float) y, (float) z, 0f, 0f, chars[15],
				java.util.Arrays.copyOfRange(chars, 0, 3), java.util.Arrays.copyOfRange(chars, 3, 15), 0);
	}

	/** VisualMinecraft's HUD with the selected hotbar slot moving every second, so a run can see
	 *  whether new overlay frames reach the screen. */
	static void publishMovingHud(int w, int h, int selected) {
		java.lang.foreign.MemorySegment s = SkyLink.segment();
		if (s == null || w <= 0 || h <= 0) return;
		long base = SkyLink.overlayBackSlotOffset();
		java.lang.foreign.MemorySegment.copy(new byte[w * h * 4], 0, s, java.lang.foreign.ValueLayout.JAVA_BYTE, base, w * h * 4);
		int unit = Math.max(2, h / 270);
		int barW = 182 * unit, barH = 22 * unit, bx = (w - barW) / 2, by = h - barH - unit * 2;
		for (int yTop = by; yTop < by + barH; yTop++) {
			int y = h - 1 - yTop;
			for (int x = bx; x < bx + barW; x++) {
				int slot = (x - bx) / (20 * unit);
				boolean edge = (x - bx) % (20 * unit) < unit || yTop - by < unit || by + barH - yTop <= unit;
				int c = edge ? 0xFF606060 : (slot == selected ? 0xFF2A9DF4 : 0xB0202020);
				s.set(java.lang.foreign.ValueLayout.JAVA_INT, base + ((long) y * w + x) * 4, c);
			}
		}
		SkyLink.publishOverlay(w, h, true, System.nanoTime());
	}

	public static void main(String[] args) throws Exception {
		long total = Long.parseLong(args.length > 0 ? args[0] : "90") * 1000;
		String skin = args.length > 1 ? args[1] : null;
		int cameraMode = args.length > 2 ? Integer.parseInt(args[2]) : 0;
		long start = System.currentTimeMillis();
		var sky = new SkyLink.SkyState();
		var mc = new SkyLink.McState();
		int lastSeq = -1, ack = -1;
		double[] cur = null, prev = null, hole = null;
		double vy = 0;
		boolean onGround = false, sent = false;
		long nextTick = 0, nextOverlay = 0, standingSince = 0;
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
				if (onGround && standingSince == 0) standingSince = now;
				// A second on the ground, so the collision round us has arrived.
				if (onGround && !sent && now - standingSince > 1000) {
					double r = Math.toRadians(sky.yaw);
					int cx = (int) Math.floor(cur[0] - Math.sin(r) * 6), cz = (int) Math.floor(cur[2] + Math.cos(r) * 6);
					List<int[]> dug = new ArrayList<>();
					for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
						double g = GroundedMinecraft.ground(cx + dx + 0.5, cur[1] + 4, cz + dz + 0.5, 4);
						if (g == Double.NEGATIVE_INFINITY) continue;
						int gy = surfaceCell(g);
						dug.add(new int[] {cx + dx, gy, cz + dz});
						dug.add(new int[] {cx + dx, gy - 1, cz + dz});
					}
					// The block, two cells to the hole's side, in the cell the land runs through.
					int bx = cx + 3, bz = cz;
					double bg = GroundedMinecraft.ground(bx + 0.5, cur[1] + 4, bz + 0.5, 4);
					int by = surfaceCell(bg);
					ByteBuffer block = ByteBuffer.allocateDirect(36 * 32).order(ByteOrder.LITTLE_ENDIAN);
					VisualMinecraft.cube(block, Math.floorMod(bx, 16), Math.floorMod(by, 16), Math.floorMod(bz, 16));
					boolean ok = VisualMinecraft.sendAtlas()
							&& SkyLink.writeRender(Proto.REN_SECTION, le(16).putInt(Math.floorDiv(bx, 16)).putInt(Math.floorDiv(by, 16)).putInt(Math.floorDiv(bz, 16)).putInt(36).flip(), block.flip())
							&& sendCells(Proto.REN_CUT, List.of(new int[] {bx, by, bz}), 0)
							&& sendCells(Proto.REN_DUG, dug, sky.worldId)
							&& (skin == null || sendSkin(skin));
					hole = new double[] {cx + 0.5, cur[1], cz + 0.5};
					System.out.printf("cut minecraft: hole at %d %d (%d cells), block at %d %d %d, world %d, viewport %dx%d (flags %#x), sent %b%n", cx, cz, dug.size(), bx, by, bz, sky.worldId, sky.viewportW, sky.viewportH, sky.flags, ok);
					sent = true;
				}
				if (hole != null) {
					// Steve walks round the hole; his shadow follows him on the ground.
					double t = (now - start) / 1000.0;
					double px = hole[0] + Math.cos(t * 0.6) * 3, pz = hole[2] + Math.sin(t * 0.6) * 3;
					double pg = GroundedMinecraft.ground(px, hole[1] + 4, pz, 4);
					double py = pg == Double.NEGATIVE_INFINITY ? hole[1] : pg;
					List<SkyLink.WorldEntity> list = new ArrayList<>();
					list.add(partner(77, "Steve", px, py, pz));
					list.add(new SkyLink.WorldEntity(Proto.WE_SHADOW, 77, (float) px, (float) py, (float) pz, 0f, 0f, 0.6f, null, null, 0));
					SkyLink.writeWorldEntities(list, null);
				}
				if (onGround && now >= nextOverlay) {
					nextOverlay = now + 100;
					publishMovingHud(sky.viewportW, sky.viewportH, (int) ((now - start) / 1000 % 9));
				}
				mc.flags = Proto.MC_IN_WORLD | (onGround ? Proto.MC_ON_GROUND : 0);
				mc.x = cur[0]; mc.y = cur[1]; mc.z = cur[2];
				mc.yaw = sky.yaw; mc.pitch = sky.pitch; mc.eyeHeight = 1.62f;
				mc.eyeX = cur[0]; mc.eyeY = cur[1] + 1.62; mc.eyeZ = cur[2];
				mc.fov = 70f; mc.teleportAck = known ? ack : ack - 1;
				mc.health = 20f; mc.maxHealth = 20f; mc.mouseSensitivity = 0.5f;
				mc.cameraMode = cameraMode; mc.cameraDistance = 4f;
				mc.frameCounter++;
				SkyLink.writeMcState(mc);
			}
			Thread.sleep(8);
		}
	}
}
