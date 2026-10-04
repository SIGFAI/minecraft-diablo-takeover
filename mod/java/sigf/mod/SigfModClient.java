package sigf.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sigf.kit.Sigf;

/** Client side: Diablo health and mana globes, a gold counter, and a behind-the-back camera in the demo. */
public final class SigfModClient implements ClientModInitializer {
	private static boolean camSet;

	@Override
	public void onInitializeClient() {
		HudElementRegistry.removeElement(VanillaHudElements.HEALTH_BAR);
		HudElementRegistry.removeElement(VanillaHudElements.FOOD_BAR);
		HudElementRegistry.addLast(Sigf.id("globes"), SigfModClient::globes);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) { camSet = false; return; }
			if (Sigf.isDemo() && !camSet) { mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); camSet = true; }
		});
	}

	static void globes(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		Player p = mc.player;
		if (p == null || p.isSpectator()) return;
		int w = g.guiWidth(), h = g.guiHeight();
		int r = 21;
		float hp = Math.max(0f, Math.min(1f, p.getHealth() / p.getMaxHealth()));
		float mana = Math.max(0f, Math.min(1f, p.getFoodData().getFoodLevel() / 20f));
		double t = (mc.level != null ? mc.level.getGameTime() : 0) + dt.getGameTimeDeltaPartialTick(false);
		globe(g, r + 4, h - r - 3, r, hp, t, 0xff9a0808, 0xffe83a2a, 0xff3a0505);
		globe(g, w - r - 4, h - r - 3, r, mana, t + 40, 0xff0a2a9a, 0xff3a8aff, 0xff05123a);
		int gold = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(SigfMod.GOLD_PILE)) gold += s.getCount();
		}
		g.text(mc.font, "Gold: " + gold, r * 2 + 12, h - 12, 0xffffd24a, true);
	}

	/** A round glass globe whose liquid rises with `fill`. */
	static void globe(GuiGraphicsExtractor g, int cx, int cy, int r, float fill, double t, int dark, int bright, int empty) {
		for (int dy = -r - 2; dy <= r + 2; dy++) {
			int hw = (int) Math.round(Math.sqrt((r + 2.0) * (r + 2.0) - dy * dy));
			g.fill(cx - hw, cy + dy, cx + hw, cy + dy + 1, 0xff5a4318);
		}
		double top = cy + r - fill * 2 * r;
		for (int dy = -r; dy <= r; dy++) {
			int hw = (int) Math.round(Math.sqrt((double) r * r - dy * dy));
			int y = cy + dy;
			double wave = Math.sin(t * 0.25 + dy * 0.2) * 0.8;
			boolean liquid = y + wave >= top;
			int col;
			if (liquid) {
				float k = (float) (dy + r) / (2 * r);
				col = mix(bright, dark, k);
			} else col = empty;
			g.fill(cx - hw, y, cx + hw, y + 1, col);
		}
		// glass highlight
		g.fill(cx - r / 2, cy - r / 2, cx - r / 4, cy - r / 4, 0x66ffffff);
		g.fill(cx - r / 2 - 2, cy - r / 2 + 2, cx - r / 2, cy, 0x44ffffff);
	}

	static int mix(int a, int b, float k) {
		int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
		int gr = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
		int bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
		return 0xff000000 | r << 16 | gr << 8 | bl;
	}
}
