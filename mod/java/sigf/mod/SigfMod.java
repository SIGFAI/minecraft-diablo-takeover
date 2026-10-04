package sigf.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import sigf.kit.Sigf;

/** Diablo Takeover: Tristram falls on the overworld. Hordes, champions, bosses, loot beams, Whirlwind. */
public final class SigfMod implements ModInitializer {
	public static Item HEALTH_GLOBE, TOWN_SCROLL, DOOM_SWORD, GOLD_PILE;
	public static SoundEvent ROAR, LOOT, BELL, FRESH_MEAT, STAY_AWHILE;

	static final String TAG = "dt";
	static final List<Boss> bosses = new ArrayList<>();
	static final List<Vec3> beams = new ArrayList<>();
	static final List<long[]> beamLife = new ArrayList<>();
	static final List<int[]> beamColor = new ArrayList<>();
	static final Map<java.util.UUID, Long> whirlCd = new HashMap<>();
	static int numCounter = 0;
	static long now = 0;
	static boolean botOn = false;

	record Boss(Mob mob, ServerBossEvent bar) {}

	@Override
	public void onInitialize() {
		HEALTH_GLOBE = Sigf.item("health_globe", p -> new Item(p.stacksTo(16)
			.component(DataComponents.ITEM_NAME, Component.literal("Health Globe").withStyle(ChatFormatting.RED))));
		TOWN_SCROLL = Sigf.item("town_scroll", p -> new Item(p.stacksTo(16)
			.component(DataComponents.ITEM_NAME, Component.literal("Scroll of Town Portal").withStyle(ChatFormatting.AQUA))));
		DOOM_SWORD = Sigf.item("doom_sword", p -> new Item(p.sword(ToolMaterial.NETHERITE, 4f, -2.2f).stacksTo(1)
			.component(DataComponents.ITEM_NAME, Component.literal("Doombringer (Right-click: Whirlwind)").withStyle(ChatFormatting.GOLD))));
		GOLD_PILE = Sigf.item("gold_pile", p -> new Item(p.stacksTo(64)
			.component(DataComponents.ITEM_NAME, Component.literal("Gold").withStyle(ChatFormatting.YELLOW))));
		ROAR = Sigf.registerSound("demon_roar");
		LOOT = Sigf.registerSound("loot_drop");
		BELL = Sigf.registerSound("bell");
		FRESH_MEAT = Sigf.registerSound("fresh_meat");
		STAY_AWHILE = Sigf.registerSound("stay_awhile");

		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack s = player.getItemInHand(hand);
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (s.is(DOOM_SWORD)) { if (whirlwind(sp)) return InteractionResult.SUCCESS; }
			else if (s.is(HEALTH_GLOBE)) { drinkGlobe(sp); s.shrink(1); return InteractionResult.SUCCESS; }
			else if (s.is(TOWN_SCROLL)) { townPortal(sp); s.shrink(1); return InteractionResult.SUCCESS; }
			return InteractionResult.PASS;
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (!entity.entityTags().contains(TAG) || taken <= 0) return;
			Vec3 p = entity.position().add(0, entity.getBbHeight() + 0.2, 0);
			Sigf.particles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()), entity.position().add(0, entity.getBbHeight() * 0.6, 0), 14, 0.25);
			floatText(p, String.valueOf(Math.round(taken)), taken >= 10 ? "gold" : "white", taken >= 10);
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!entity.entityTags().contains(TAG)) return;
			onKill(entity);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> tick());

		Sigf.after(0.5, SigfMod::setStage);
		Sigf.every(8, () -> { if (Sigf.host() != null) horde(4 + (int) (Math.random() * 3)); });
		Sigf.every(22, () -> { if (Sigf.host() != null) champion(); });
		Sigf.every(3, () -> Sigf.command("time set 12500"));

		scenario();
	}

	// ---------- stage ----------

	static void setStage() {
		Sigf.command("time set 12500");
		Sigf.command("weather clear");
		Sigf.command("gamerule fire_spread_radius_around_player 0");
		Sigf.command("team add dt_gold");
		Sigf.command("team modify dt_gold color gold");
		Sigf.command("team add dt_blue");
		Sigf.command("team modify dt_blue color aqua");
		Sigf.command("team add dt_red");
		Sigf.command("team modify dt_red color red");
		Sigf.command("team add dt_yellow");
		Sigf.command("team modify dt_yellow color yellow");
		if (Sigf.host() != null && Sigf.isDemo()) {
			Vec3 a = findArena(Sigf.host().position());
			if (a != null) Sigf.teleport(Sigf.host(), a, Vec3.ZERO);
		}
		// braziers around the stage
		Vec3 c = Sigf.host() != null ? Sigf.host().position() : Vec3.atCenterOf(Sigf.level().getRespawnData().pos());
		for (int i = 0; i < 10; i++) {
			double a = i * Math.PI * 2 / 10;
			Vec3 g = Sigf.ground(c.add(Math.cos(a) * 11, 0, Math.sin(a) * 11), 0.1);
			if (!dry(g)) continue;
			var pos = net.minecraft.core.BlockPos.containing(g);
			for (int k = 0; k < 3; k++) Sigf.level().setBlockAndUpdate(pos.above(k), (k == 1 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState());
			Sigf.level().setBlockAndUpdate(pos.above(3), Blocks.CAMPFIRE.defaultBlockState());
		}
	}

	/** A dry, flat open spot near the start, so the fight never ends up in a lake. */
	static Vec3 findArena(Vec3 from) {
		for (int t = 0; t < 40; t++) {
			double ang = Math.random() * Math.PI * 2, dist = t == 0 ? 0 : 8 + Math.random() * 40;
			Vec3 c = Sigf.ground(from.add(Math.cos(ang) * dist, 0, Math.sin(ang) * dist), 0.1);
			boolean ok = dry(c);
			for (int i = 0; i < 12 && ok; i++) {
				double a = i * Math.PI / 6;
				Vec3 g = Sigf.ground(c.add(Math.cos(a) * 14, 0, Math.sin(a) * 14), 0.1);
				if (!dry(g) || Math.abs(g.y - c.y) > 4) ok = false;
			}
			if (ok) return c;
		}
		return null;
	}

	static void joinTeam(Entity e, String team) { Sigf.command("team join " + team + " " + e.getStringUUID()); }

	// ---------- monsters ----------

	static Vec3 aheadOfHost(double dist, double spread) {
		ServerPlayer h = Sigf.host();
		Vec3 look = h.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0, look.z);
		flat = flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
		Vec3 base = h.position().add(flat.scale(dist));
		for (int i = 0; i < 12 && !dry(Sigf.ground(base, 0.1)); i++) {
			double a = Math.random() * Math.PI * 2;
			base = h.position().add(Math.cos(a) * dist, 0, Math.sin(a) * dist);
		}
		return land(base, spread);
	}

	static boolean dry(Vec3 g) {
		var bp = net.minecraft.core.BlockPos.containing(g);
		return Sigf.level().getFluidState(bp).isEmpty() && Sigf.level().getFluidState(bp.below()).isEmpty();
	}

	/** A ground spot that is not water. */
	static Vec3 land(Vec3 around, double radius) {
		Vec3 g = Sigf.ground(around, radius);
		for (int i = 0; i < 25 && !dry(g); i++) g = Sigf.ground(around, radius + i);
		return g;
	}

	static void tag(Mob m, String name, ChatFormatting color, double hp, double scale) {
		m.addTag(TAG);
		m.setCustomName(Component.literal(name).withStyle(color));
		m.setCustomNameVisible(true);
		m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
		m.setHealth((float) hp);
		m.getAttribute(Attributes.SCALE).setBaseValue(scale);
		m.setPersistenceRequired();
	}

	static void horde(int n) {
		Vec3 center = aheadOfHost(7, 2);
		for (int i = 0; i < n; i++) {
			Vec3 pos = land(center, 4);
			Zombie z = Sigf.spawn(EntityTypes.ZOMBIE, pos);
			if (z == null) continue;
			z.setBaby(true);
			tag(z, "Fallen", ChatFormatting.RED, 16, 1.0);
			ItemStack cap = new ItemStack(Items.LEATHER_HELMET);
			cap.set(DataComponents.DYED_COLOR, new DyedItemColor(0x8a0b0b));
			z.setItemSlot(EquipmentSlot.HEAD, cap);
			z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
			z.setDropChance(EquipmentSlot.HEAD, 0f);
			z.setDropChance(EquipmentSlot.MAINHAND, 0f);
			Sigf.particles(ParticleTypes.FLAME, pos.add(0, 0.3, 0), 10, 0.3);
			Sigf.particles(ParticleTypes.SMOKE, pos.add(0, 0.3, 0), 10, 0.3);
		}
		Sigf.sound(SoundEvents.ZOMBIE_VILLAGER_CONVERTED, center, 1.2f, 0.6f);
	}

	static final String[] AFFIX = {"Fire Enchanted", "Extra Fast", "Cursed", "Mana Burn", "Teleporter"};

	static void champion() {
		int a = (int) (Math.random() * AFFIX.length);
		boolean rare = Math.random() < 0.4;
		Vec3 pos = aheadOfHost(9, 3);
		Zombie z = Sigf.spawn(EntityTypes.ZOMBIE, pos);
		if (z == null) return;
		String name = (rare ? "Gorefang the " : "") + AFFIX[a] + " Zombie";
		tag(z, name, rare ? ChatFormatting.YELLOW : ChatFormatting.AQUA, rare ? 50 : 35, rare ? 1.5 : 1.3);
		z.addTag(rare ? "dt_rare" : "dt_magic");
		z.setGlowingTag(true);
		joinTeam(z, rare ? "dt_yellow" : "dt_blue");
		ItemStack hat = new ItemStack(Items.IRON_HELMET);
		z.setItemSlot(EquipmentSlot.HEAD, hat);
		z.setDropChance(EquipmentSlot.HEAD, 0f);
		if (a == 0) { z.addTag("dt_fire"); z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD)); }
		if (a == 1) z.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED, 20 * 60, 2));
		Sigf.particles(ParticleTypes.SOUL_FIRE_FLAME, pos.add(0, 1, 0), 30, 0.5);
		Sigf.sound(SoundEvents.WITHER_SPAWN, pos, 0.4f, 1.6f);
		Sigf.title("", rare ? "A rare monster appears" : "A champion pack appears", 2);
	}

	static void butcher() {
		Vec3 pos = aheadOfHost(12, 1);
		Zombie z = Sigf.spawn(EntityTypes.ZOMBIE, pos);
		if (z == null) return;
		tag(z, "The Butcher", ChatFormatting.DARK_RED, 85, 1.8);
		z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.36);
		z.addTag("dt_boss");
		z.setGlowingTag(true);
		joinTeam(z, "dt_red");
		z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
		z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		z.setDropChance(EquipmentSlot.MAINHAND, 0f);
		z.setDropChance(EquipmentSlot.HEAD, 0f);
		addBoss(z, "The Butcher", BossEvent.BossBarColor.RED);
		Sigf.sound(FRESH_MEAT, Sigf.host().position(), 2f, 1f);
		Sigf.sound(ROAR, pos, 2f, 0.9f);
		Sigf.title("THE BUTCHER", "\"Ahhh, fresh meat!\"", 3);
		lightning(pos);
	}

	static void diablo() {
		Vec3 pos = aheadOfHost(14, 1);
		var z = Sigf.spawn(EntityTypes.WITHER_SKELETON, pos);
		if (z == null) return;
		tag(z, "Diablo, Lord of Terror", ChatFormatting.DARK_RED, 200, 2.5);
		z.addTag("dt_boss");
		z.setGlowingTag(true);
		joinTeam(z, "dt_red");
		z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
		z.setDropChance(EquipmentSlot.MAINHAND, 0f);
		addBoss(z, "Diablo, Lord of Terror", BossEvent.BossBarColor.PURPLE);
		Sigf.sound(ROAR, pos, 2.5f, 0.7f);
		Sigf.sound(BELL, Sigf.host().position(), 2f, 0.8f);
		Sigf.title("DIABLO", "Lord of Terror", 3);
		lightning(pos);
		Sigf.particles(ParticleTypes.FLAME, pos.add(0, 1, 0), 120, 1.5);
	}

	static void hellCows() {
		Vec3 center = aheadOfHost(10, 2);
		Sigf.title("THERE IS NO COW LEVEL", "...there is a cow level", 3);
		for (int i = 0; i < 9; i++) {
			Vec3 pos = land(center, 4);
			Cow c = Sigf.spawn(EntityTypes.COW, pos);
			if (c == null) continue;
			tag(c, "Hell Bovine", ChatFormatting.RED, 24, 1.4);
			c.addTag("dt_cow");
			c.setGlowingTag(true);
			joinTeam(c, "dt_red");
			c.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
			c.setDropChance(EquipmentSlot.HEAD, 0f);
			Sigf.particles(ParticleTypes.FLAME, pos.add(0, 0.5, 0), 12, 0.4);
			Sigf.particles(ParticleTypes.PORTAL, pos.add(0, 0.5, 0), 20, 0.6);
		}
		Sigf.sound(SoundEvents.RAVAGER_ROAR, center, 1.5f, 0.6f);
	}

	static void addBoss(Mob mob, String name, BossEvent.BossBarColor color) {
		ServerBossEvent bar = new ServerBossEvent(java.util.UUID.randomUUID(), Component.literal(name).withStyle(ChatFormatting.RED), color, BossEvent.BossBarOverlay.NOTCHED_10);
		for (ServerPlayer p : Sigf.players()) bar.addPlayer(p);
		bosses.add(new Boss(mob, bar));
	}

	static void lightning(Vec3 pos) {
		Sigf.command(String.format(java.util.Locale.ROOT, "summon lightning_bolt %f %f %f", pos.x, pos.y, pos.z));
	}

	// ---------- hits, kills, loot ----------

	static void floatText(Vec3 p, String text, String color, boolean big) {
		String tag = "dn" + (numCounter++);
		String pos = String.format(java.util.Locale.ROOT, "%f %f %f", p.x + (Math.random() - 0.5) * 0.8, p.y, p.z + (Math.random() - 0.5) * 0.8);
		String sc = big ? "2.2f" : "1.4f";
		Sigf.command("summon text_display " + pos + " {Tags:[\"" + tag + "\",\"dtnum\"],billboard:\"center\",text:{text:\"" + text + "\",color:\"" + color + "\",bold:true},background:0,shadow:1b,"
			+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[" + sc + "," + sc + "," + sc + "],translation:[0f,0f,0f]}}");
		Sigf.after(0.1, () -> Sigf.command("data merge entity @e[tag=" + tag + ",limit=1] {start_interpolation:0,interpolation_duration:20,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:["
			+ sc + "," + sc + "," + sc + "],translation:[0f,1.6f,0f]}}"));
		Sigf.after(1.3, () -> Sigf.command("kill @e[tag=" + tag + "]"));
	}

	static void drop(Vec3 pos, ItemStack s, String team) {
		ItemEntity ie = new ItemEntity(Sigf.level(), pos.x, pos.y + 0.5, pos.z, s);
		ie.setDeltaMovement((Math.random() - 0.5) * 0.35, 0.35, (Math.random() - 0.5) * 0.35);
		ie.setCustomNameVisible(true);
		ie.setCustomName(s.getHoverName());
		if (team != null) { ie.setGlowingTag(true); Sigf.level().addFreshEntity(ie); joinTeam(ie, team); }
		else Sigf.level().addFreshEntity(ie);
	}

	static void beam(Vec3 pos, int rgb, double seconds) {
		beams.add(pos);
		beamLife.add(new long[] { now + Math.round(seconds * 20) });
		beamColor.add(new int[] { rgb });
	}

	static void onKill(LivingEntity e) {
		Vec3 pos = e.position();
		Sigf.particles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()), pos.add(0, 0.5 * e.getBbHeight(), 0), 50, 0.5);
		Sigf.particles(ParticleTypes.SOUL, pos.add(0, 0.5, 0), 12, 0.4);
		boolean boss = e.entityTags().contains("dt_boss"), rare = e.entityTags().contains("dt_rare"), magic = e.entityTags().contains("dt_magic");
		boolean cow = e.entityTags().contains("dt_cow");
		int gold = boss ? 40 : rare ? 25 : magic ? 15 : 4 + (int) (Math.random() * 6);
		if (boss || rare || magic || cow || Math.random() < 0.7) {
			drop(pos, new ItemStack(GOLD_PILE, Math.min(64, gold)), (boss || rare || magic) ? "dt_gold" : null);
			Sigf.sound(SoundEvents.AMETHYST_BLOCK_CHIME, pos, 1.2f, 0.7f);
		}
		if (Math.random() < 0.18 || boss || rare || magic || cow && Math.random() < 0.4) {
			drop(pos, new ItemStack(HEALTH_GLOBE), "dt_red");
			beam(pos, 0xff3333, 5);
		}
		if (boss || (rare && Math.random() < 0.5)) {
			drop(pos, new ItemStack(DOOM_SWORD), "dt_gold");
			beam(pos, 0xffaa00, 14);
			Sigf.sound(LOOT, pos, 2f, 1f);
			Sigf.title("", "Legendary item dropped!", 2);
		} else if (rare || magic) {
			drop(pos, new ItemStack(TOWN_SCROLL), "dt_blue");
			beam(pos, rare ? 0xffff55 : 0x55ffff, 10);
			Sigf.sound(LOOT, pos, 1.2f, 1.2f);
		}
		if (boss) {
			lightning(pos);
			Sigf.sound(ROAR, pos, 1.5f, 1.3f);
			Sigf.particles(ParticleTypes.EXPLOSION_EMITTER, pos.add(0, 1, 0), 1, 0.1);
			Sigf.title("YOU HAVE SLAIN " + e.getName().getString().toUpperCase(), "", 3);
		}
	}

	// ---------- skills ----------

	static boolean whirlwind(ServerPlayer p) {
		long cd = whirlCd.getOrDefault(p.getUUID(), 0L);
		if (now < cd) return false;
		whirlCd.put(p.getUUID(), now + 60);
		Sigf.sound(SoundEvents.PLAYER_ATTACK_SWEEP, p.position(), 1.5f, 0.6f);
		float y0 = p.getYRot();
		for (int i = 1; i <= 8; i++) {
			final int k = i;
			Sigf.after(0.07 * i, () -> {
				p.connection.teleport(p.getX(), p.getY(), p.getZ(), y0 + k * 45f, p.getXRot());
				Vec3 c = p.position().add(0, 1, 0);
				for (int j = 0; j < 12; j++) {
					double a = j * Math.PI / 6 + k * 0.8;
					Vec3 r = c.add(Math.cos(a) * 2.6, 0, Math.sin(a) * 2.6);
					Sigf.particles(ParticleTypes.SWEEP_ATTACK, r, 1, 0.05);
					if (j % 3 == 0) Sigf.particles(ParticleTypes.FLAME, r, 2, 0.1);
				}
				if (k == 2 || k == 5 || k == 8) {
					for (Entity e : Sigf.near(p.position(), 4.6, x -> x instanceof LivingEntity && x != p)) {
						LivingEntity l = (LivingEntity) e;
						l.hurtServer(Sigf.level(), Sigf.level().damageSources().playerAttack(p), 9f);
						Vec3 d = l.position().subtract(p.position());
						d = new Vec3(d.x, 0, d.z);
						d = d.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : d.normalize();
						Sigf.teleport(l, l.position(), new Vec3(d.x * 0.55, 0.3, d.z * 0.55));
					}
					Sigf.sound(SoundEvents.PLAYER_ATTACK_STRONG, p.position(), 1.2f, 0.7f);
				}
			});
		}
		return true;
	}

	static void drinkGlobe(ServerPlayer p) {
		p.heal(20f);
		p.getFoodData().setFoodLevel(20);
		Sigf.particles(ParticleTypes.HEART, p.position().add(0, 1.5, 0), 10, 0.4);
		Sigf.sound(SoundEvents.GENERIC_DRINK.value(), p.position(), 1.2f, 0.7f);
		Sigf.sound(SoundEvents.BREWING_STAND_BREW, p.position(), 0.7f, 1.4f);
	}

	static void townPortal(ServerPlayer p) {
		Vec3 a = p.position().add(p.getLookAngle().scale(2.5));
		a = land(a, 0.5);
		Vec3 b = land(Vec3.atCenterOf(Sigf.level().getRespawnData().pos()), 3);
		Sigf.portal(a, b, 30).particles(ParticleTypes.SOUL_FIRE_FLAME, ParticleTypes.END_ROD);
		Sigf.sound(SoundEvents.END_PORTAL_SPAWN, a, 1f, 1.4f);
		Sigf.title("", "Town Portal opened", 2);
	}

	// ---------- per-tick effects ----------

	static void tick() {
		now++;
		// loot beams
		for (int i = beams.size() - 1; i >= 0; i--) {
			if (now > beamLife.get(i)[0]) { beams.remove(i); beamLife.remove(i); beamColor.remove(i); continue; }
			if (now % 2 == 0) {
				Vec3 b = beams.get(i);
				for (int h = 0; h < 14; h++) Sigf.level().sendParticles(ParticleTypes.END_ROD, b.x, b.y + h * 0.9, b.z, 1, 0.05, 0.05, 0.05, 0.0);
				Sigf.level().sendParticles(ParticleTypes.FLAME, b.x, b.y + 0.3, b.z, 2, 0.3, 0.1, 0.3, 0.01);
			}
		}
		// boss bars
		for (int i = bosses.size() - 1; i >= 0; i--) {
			Boss b = bosses.get(i);
			if (!b.mob.isAlive()) { b.bar.removeAllPlayers(); bosses.remove(i); continue; }
			if (now % 100 == 0) Sigf.log("boss " + b.mob.getName().getString() + " hp=" + b.mob.getHealth() + " at " + b.mob.position() + " dist=" + b.mob.distanceTo(Sigf.host()));
			b.bar.setProgress(b.mob.getHealth() / b.mob.getMaxHealth());
			for (ServerPlayer p : Sigf.players()) b.bar.addPlayer(p);
			if (now % 80 == 0 && b.mob.entityTags().contains("dt_boss") && b.mob.getBbHeight() > 4) {
				Vec3 t = b.mob.position();
				Sigf.particles(ParticleTypes.FLAME, t.add(0, 2, 0), 40, 1.2);
				Sigf.sound(SoundEvents.WITHER_AMBIENT, t, 1.5f, 0.6f);
			}
		}
		if (now % 20 == 0 && Sigf.host() != null) leash();
		if (now % 4 == 0) {
			ServerLevel lv = Sigf.level();
			// elite auras
			for (Entity e : lv.getEntities((Entity) null, new net.minecraft.world.phys.AABB(-3e7, -64, -3e7, 3e7, 400, 3e7), x -> x.entityTags().contains(TAG) && x.isAlive() && (x.entityTags().contains("dt_magic") || x.entityTags().contains("dt_rare") || x.entityTags().contains("dt_boss") || x.entityTags().contains("dt_cow")))) {
				Vec3 pp = e.position().add(0, e.getBbHeight() * 0.5, 0);
				if (e.entityTags().contains("dt_fire") || e.entityTags().contains("dt_boss") || e.entityTags().contains("dt_cow")) Sigf.particles(ParticleTypes.FLAME, pp, 3, 0.4);
				else Sigf.particles(e.entityTags().contains("dt_rare") ? ParticleTypes.ENCHANT : ParticleTypes.SOUL_FIRE_FLAME, pp, 3, 0.5);
				if (e instanceof Cow c && Sigf.host() != null && c.distanceTo(Sigf.host()) < 30) c.getNavigation().moveTo(Sigf.host(), 1.9);
				if (e instanceof Cow c && Sigf.host() != null && c.distanceTo(Sigf.host()) < 2.2 && now % 20 == 0) {
					Sigf.host().hurtServer(lv, lv.damageSources().mobAttack(c), 3f);
					Sigf.sound(SoundEvents.RAVAGER_HURT, c.position(), 1f, 0.8f);
				}
			}
			// health globes pick up on touch
			for (ServerPlayer p : Sigf.players()) {
				for (Entity e : Sigf.near(p.position(), 1.8, x -> x instanceof ItemEntity ie && ie.getItem().is(HEALTH_GLOBE))) {
					drinkGlobe(p);
					((ItemEntity) e).getItem().shrink(1);
					e.discard();
				}
			}
		}
		if (Sigf.isDemo() && botOn && Sigf.host() != null && now % 5 == 0) bot();
	}

	/** Keeps the monsters in the fight: stragglers and swimmers are put back on dry land near the player. */
	static void leash() {
		ServerPlayer h = Sigf.host();
		if (h.isSpectator()) return;
		if (h.isInWater()) Sigf.teleport(h, land(h.position(), 8), Vec3.ZERO);
		for (Entity e : Sigf.near(h.position(), 120, x -> x.entityTags().contains(TAG) && x instanceof Mob)) {
			if (e.distanceTo(h) > 22 || e.isInWater()) Sigf.teleport(e, land(aheadOfHost(8, 2), 3), Vec3.ZERO);
		}
	}

	// ---------- demo bot: a barbarian that fights ----------

	static void bot() {
		ServerPlayer h = Sigf.host();
		if (!h.getMainHandItem().is(DOOM_SWORD)) {
			for (int i = 0; i < 9; i++) if (h.getInventory().getItem(i).is(DOOM_SWORD)) { h.getInventory().setSelectedSlot(i); break; }
		}
		Entity best = null; double bd = 1e9;
		for (Entity e : Sigf.near(h.position(), 26, x -> x.entityTags().contains(TAG) && x instanceof LivingEntity)) {
			double d = e.distanceTo(h) - (e.entityTags().contains("dt_boss") ? 18 : 0);
			if (d < bd) { bd = d; best = e; }
		}
		if (h.getHealth() < 10f) {
			for (int i = 0; i < h.getInventory().getContainerSize(); i++) {
				ItemStack st = h.getInventory().getItem(i);
				if (st.is(HEALTH_GLOBE)) { drinkGlobe(h); st.shrink(1); break; }
			}
		}
		if (best == null) return;
		bd = best.distanceTo(h);
		Sigf.lookAt(h, best.position().add(0, best.getBbHeight() * 0.5, 0));
		double reach = 3.4 + best.getBbWidth() * 0.6;
		if (bd > reach) {
			Vec3 d = best.position().subtract(h.position());
			d = new Vec3(d.x, 0, d.z).normalize();
			double step = Math.min(1.0, bd - reach + 0.2);
			Vec3 np = h.position().add(d.scale(step));
			var top = Sigf.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, net.minecraft.core.BlockPos.containing(np));
			Vec3 dest = new Vec3(np.x, top.getY(), np.z);
			if (dry(dest) && Math.abs(top.getY() - h.getY()) < 4) Sigf.teleport(h, dest, new Vec3(0, 0, 0));
		} else if (now % 10 == 0) {
			h.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			h.resetAttackStrengthTicker();
			h.attack(best);
		}
		int near = Sigf.near(h.position(), 5, x -> x.entityTags().contains(TAG)).size();
		if (near >= 3 || best.entityTags().contains("dt_boss") && bd < 5) whirlwind(h);
	}

	// ---------- the clip ----------

	static void scenario() {
		Sigf.demo(0.5, () -> {
			ServerPlayer h = Sigf.host();
			Sigf.command("give @a sigf:doom_sword");
			Sigf.command("give @a sigf:town_scroll 2");
			Sigf.command("give @a sigf:health_globe 3");
			Sigf.title("DIABLO TAKEOVER", "Tristram has fallen", 3);
			Sigf.sound(BELL, h.position(), 2f, 1f);
			Sigf.sound(STAY_AWHILE, h.position(), 2f, 1f);
			Sigf.say("<Deckard Cain> Stay awhile, and listen...");
			for (var slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
				Item it = slot == EquipmentSlot.HEAD ? Items.NETHERITE_HELMET : slot == EquipmentSlot.CHEST ? Items.NETHERITE_CHESTPLATE : slot == EquipmentSlot.LEGS ? Items.NETHERITE_LEGGINGS : Items.NETHERITE_BOOTS;
				h.setItemSlot(slot, new ItemStack(it));
			}
			horde(11);
		});
		Sigf.demo(3.5, () -> botOn = true);
		Sigf.demo(7, () -> horde(6));
		Sigf.demo(14, () -> Sigf.host().setHealth(4f));
		Sigf.demo(16, () -> champion());
		Sigf.demo(20, () -> { Sigf.say("<Deckard Cain> The Butcher is coming..."); butcher(); });
		Sigf.demo(36, () -> horde(5));
		Sigf.demo(44, () -> hellCows());
		Sigf.demo(56, () -> townPortal(Sigf.host()));
		Sigf.demo(60, () -> diablo());
	}
}
