import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * GroundedMinecraft standing still, fighting: it reads the game's actor table through the patched
 * mod's SkyLink, hits the nearest creature it may (hostile or neutral) (EV_HIT_ACTOR, a stone sword's 5) until it's
 * dead, takes what the game sends back (IN_HURT on its own hearts, IN_DROP and IN_GIVE loot and
 * experience, IN_CHARACTER), dies at a given second (MC_DEAD and EV_PLAYER_DIED) and respawns a
 * little later. It also publishes a world entity table with a dropped item and a block outline,
 * and reads the water grid. Everything it sees is printed, so a run shows each leg of combat,
 * life and gifts.
 *
 *   java ... CombatMinecraft.java <total seconds> <die at second, 0 for never>
 */
public final class CombatMinecraft {
	static float health = 20f;
	static int hurts, gives, drops, characters;
	static boolean listed;

	static void inputs() {
		SkyLink.drainInput((kind, code, a, b, c) -> {
			switch (kind) {
				case Proto.IN_HURT -> {
					float mc = a / 100f / 5f;
					health = Math.max(0f, health - mc);
					hurts++;
					System.out.printf("combat minecraft: IN_HURT kind %d for %.2f (attacker %d, flags %d): health %.2f%n", code, mc, b, c, health);
				}
				case Proto.IN_GIVE -> {
					gives++;
					System.out.printf("combat minecraft: IN_GIVE %d x item %d (enchant %d)%n", a, code, b);
				}
				case Proto.IN_DROP -> {
					drops++;
					System.out.printf("combat minecraft: IN_DROP %d x item %d at %.1f %.1f %.1f%n", code >>> 8, code & 0xFF, a / 8.0, b / 8.0, c / 8.0);
				}
				case Proto.IN_CHARACTER -> {
					characters++;
					System.out.printf("combat minecraft: IN_CHARACTER %x%n", ((long) b << 32) | (a & 0xFFFFFFFFL));
				}
				default -> {}
			}
		});
	}

	public static void main(String[] args) throws Exception {
		long total = Long.parseLong(args.length > 0 ? args[0] : "120") * 1000;
		long dieAt = Long.parseLong(args.length > 1 ? args[1] : "0") * 1000;
		long start = System.currentTimeMillis();
		var sky = new SkyLink.SkyState();
		var mc = new SkyLink.McState();
		var actors = new ArrayList<SkyLink.Actor>();
		int lastSeq = -1, ack = -1, target = 0, hits = 0;
		double[] cur = null, prev = null;
		double vy = 0;
		boolean onGround = false, atlasSent = false, dead = false;
		long arrivedAt = 0, nextTick = 0, nextHit = 0, nextLook = 0, nextPrint = 0, diedAt = 0;
		SkyLink.announceRunning();
		while (System.currentTimeMillis() - start < total) {
			SkyLink.poll();
			GroundedMinecraft.drain();
			inputs();
			long now = System.currentTimeMillis();
			if (!(SkyLink.active() && SkyLink.readSkyState(sky) && sky.inGame())) {
				Thread.sleep(8);
				continue;
			}
			if (sky.teleportSeq != lastSeq) {
				lastSeq = sky.teleportSeq; ack = sky.teleportSeq;
				cur = new double[] {sky.x, sky.y, sky.z}; prev = cur.clone(); vy = 0;
				if (arrivedAt == 0) arrivedAt = now;
				System.out.printf("combat minecraft: put at %.2f %.2f %.2f (teleport %d)%n", sky.x, sky.y, sky.z, ack);
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
			if (onGround && !atlasSent) atlasSent = VisualMinecraft.sendAtlas();
			// Death at the given second; a respawn eight seconds on.
			if (!dead && dieAt > 0 && now - start >= dieAt) {
				dead = true; diedAt = now; health = 0f;
				SkyLink.pushEvent(Proto.EV_PLAYER_DIED, 0, 0f, 0f, 0f, 0f, 0);
				System.out.println("combat minecraft: died");
			} else if (!dead && health <= 0f) {
				dead = true; diedAt = now;
				SkyLink.pushEvent(Proto.EV_PLAYER_DIED, 0, 0f, 0f, 0f, 0f, 0);
				System.out.println("combat minecraft: killed by the game's creatures");
			}
			if (dead && now - diedAt > 8000) {
				dead = false; health = 20f; dieAt = 0;
				System.out.printf("combat minecraft: respawned (game ghost %b)%n", (sky.flags & Proto.SKY_GHOST) != 0);
			}
			// The actor table, and a fight with the nearest hostile.
			if (now >= nextLook) {
				nextLook = now + 250;
				if (SkyLink.readActors(actors)) {
					final double px = cur[0], py = cur[1], pz = cur[2];
					List<SkyLink.Actor> hostile = actors.stream()
						.filter(a -> !a.dead() && (a.flags() & (Proto.ACTOR_FRIENDLY | Proto.ACTOR_ORE)) == 0 && a.level() > 0 && a.width() < 3f && !a.name().isEmpty())
						.sorted(Comparator.comparingDouble(a -> Math.pow(a.x() - px, 2) + Math.pow(a.y() - py, 2) + Math.pow(a.z() - pz, 2)))
						.toList();
					SkyLink.Actor t = actors.stream().filter(a -> a.formId() == target_[0]).findFirst().orElse(null);
					if (t != null && t.dead()) {
						System.out.printf("combat minecraft: %s (%d) is dead after %d hits%n", t.name(), t.formId(), hits);
						target_[0] = 0;
					}
					if (target_[0] == 0 && !hostile.isEmpty() && onGround && now - arrivedAt > 5000) {
						SkyLink.Actor h = hostile.getFirst();
						target_[0] = h.formId(); hits = 0;
						System.out.printf("combat minecraft: fighting %s (form %d, level %d, health %.2f) at %.1f %.1f %.1f, %.1f away%n",
							h.name(), h.formId(), h.level(), h.healthFrac(), h.x(), h.y(), h.z(), Math.sqrt(Math.pow(h.x() - px, 2) + Math.pow(h.z() - pz, 2)));
					}
				}
			}
			if (target_[0] != 0 && !dead && now >= nextHit && (dieAt == 0 || now - start < dieAt - 2000)) {
				nextHit = now + 800;
				SkyLink.pushEvent(Proto.EV_HIT_ACTOR, target_[0], 5f, 0f, 0f, 0.4f, 0);
				hits++;
			}
			// A dropped item at our feet's front, and the outline of the block under us.
			if (onGround) {
				var list = new ArrayList<SkyLink.WorldEntity>();
				double r = Math.toRadians(sky.yaw);
				float ix = (float) (cur[0] - Math.sin(r) * 2), iz = (float) (cur[2] + Math.cos(r) * 2);
				list.add(new SkyLink.WorldEntity(Proto.WE_ITEM, 1, ix, (float) cur[1] + 0.4f, iz, 0f, 0f, 0.5f, null, new float[] {0f, 0f, 0.5f, 1f}, 0));
				int bx = (int) Math.floor(ix), by = (int) Math.floor(cur[1]) - 1, bz = (int) Math.floor(iz);
				SkyLink.writeWorldEntities(list, new float[] {bx, by, bz, bx + 1, by + 1, bz + 1});
			}
			if (now >= nextPrint) {
				nextPrint = now + 5000;
				long n = actors.size(), h = actors.stream().filter(SkyLink.Actor::hostile).count();
				long f = actors.stream().filter(a -> (a.flags() & Proto.ACTOR_FRIENDLY) != 0).count();
				long o = actors.stream().filter(a -> (a.flags() & Proto.ACTOR_ORE) != 0).count();
				long tr = actors.stream().filter(a -> (a.flags() & Proto.ACTOR_TRADER) != 0).count();
				var water = SkyLink.readWaterGrid();
				int wet = 0;
				if (water != null) for (float s : water.surface) if (s > -1e20f) wet++;
				if (!listed && n > 0) {
					listed = true;
					final double qx = cur[0], qz = cur[2];
					actors.stream().sorted(Comparator.comparingDouble(a -> Math.pow(a.x() - qx, 2) + Math.pow(a.z() - qz, 2))).limit(8).forEach(a ->
						System.out.printf("combat minecraft:   %s: form %d, flags %#x, level %d, health %.2f, %.1f x %.1f, at %.1f %.1f %.1f%n", a.name(), a.formId(), a.flags(), a.level(), a.healthFrac(), a.width(), a.height(), a.x(), a.y(), a.z()));
				}
				System.out.printf("combat minecraft: %d actors (%d hostile, %d friendly, %d nodes, %d traders); water %s (%d wet columns); health %.1f; %d hurts, %d gives, %d drops, %d characters; game ghost %b%n",
					n, h, f, o, tr, water == null ? "none" : "at " + water.originX + "," + water.originZ, wet, health, hurts, gives, drops, characters, (sky.flags & Proto.SKY_GHOST) != 0);
			}
			mc.flags = Proto.MC_IN_WORLD | (onGround ? Proto.MC_ON_GROUND : 0) | (dead ? 32 : 0);
			mc.x = cur[0]; mc.y = cur[1]; mc.z = cur[2];
			mc.yaw = sky.yaw; mc.pitch = sky.pitch; mc.eyeHeight = 1.62f;
			mc.eyeX = cur[0]; mc.eyeY = cur[1] + 1.62; mc.eyeZ = cur[2];
			mc.fov = 70f; mc.teleportAck = known ? ack : ack - 1;
			mc.health = health; mc.maxHealth = 20f; mc.mouseSensitivity = 0.5f;
			mc.frameCounter++;
			SkyLink.writeMcState(mc);
			Thread.sleep(8);
		}
		System.out.printf("combat minecraft: done: %d hurts, %d gives, %d drops, %d characters%n", hurts, gives, drops, characters);
	}

	static final int[] target_ = {0};
}
