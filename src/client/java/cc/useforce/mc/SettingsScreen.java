package cc.useforce.mc;

import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The mod's settings (Mods → Force → Configure with Mod Menu, or a key binding): Minecraft's own option widgets over
 * {@link Config}. A change shows at once (open Force's view or look at the list on the game screen) and is saved when
 * the screen closes. The keys themselves are in Controls → Key Binds → Force, where Minecraft keeps every key: the
 * button here goes there.
 */
public final class SettingsScreen extends OptionsSubScreen {
    private final ForceClient mod;

    public SettingsScreen(Screen parent, ForceClient mod) {
        super(parent, Minecraft.getInstance().options, Component.translatable("force_mc.settings.title"));
        this.mod = mod;
    }

    private static Component t(String key) {
        return Component.translatable("force_mc.settings." + key);
    }

    private static <T> OptionInstance.TooltipSupplier<T> tip(String key) {
        return OptionInstance.cachedConstantTooltip(Component.translatable("force_mc.settings." + key + ".tooltip"));
    }

    private static OptionInstance<Double> percent(String key, double value, Consumer<Double> set) {
        return new OptionInstance<>("force_mc.settings." + key, tip(key),
                (caption, v) -> Options.genericValueLabel(caption, Component.literal(Math.round(v * 100) + "%")),
                OptionInstance.UnitDouble.INSTANCE, value, set::accept);
    }

    private static OptionInstance<Integer> range(String key, int min, int max, int value, String unit,
                                                 Consumer<Integer> set) {
        return new OptionInstance<>("force_mc.settings." + key, tip(key),
                (caption, v) -> Options.genericValueLabel(caption, Component.literal(v + unit)),
                new OptionInstance.IntRange(min, max), value, set::accept);
    }

    private static OptionInstance<Boolean> toggle(String key, boolean value, Consumer<Boolean> set) {
        return OptionInstance.createBoolean("force_mc.settings." + key, tip(key), value, set::accept);
    }

    @Override
    protected void addOptions() {
        Config c = mod.config();
        list.addHeader(t("view"));
        list.addSmall(
                percent("terminalOpacity", c.terminalOpacity, v -> c.terminalOpacity = v),
                range("terminalPadding", 0, 60, c.terminalPadding, " px", v -> c.terminalPadding = v),
                toggle("blur", c.blur, v -> c.blur = v),
                range("textScale", 50, 200, (int) Math.round(c.textScale * 100), "%", v -> c.textScale = v / 100.0));

        list.addHeader(t("list"));
        list.addSmall(
                percent("sidebarOpacity", c.sidebarOpacity, v -> c.sidebarOpacity = v),
                new OptionInstance<>("force_mc.settings.sidebarPosition", tip("sidebarPosition"),
                        (caption, v) -> t("position." + v),          // the button adds "Position: "
                        new OptionInstance.Enum<>(Config.POSITIONS, Codec.STRING), c.sidebarPosition,
                        v -> c.sidebarPosition = v),
                range("hudRows", 1, 20, c.hudRows, "", v -> c.hudRows = v),
                toggle("hud", c.hud, v -> c.hud = v));

        list.addHeader(t("notify"));
        list.addSmall(toggle("toasts", c.toasts, v -> c.toasts = v));

        list.addHeader(t("keys"));
        Button keys = Button.builder(t("keys.open"), b -> minecraft.gui.setScreen(new KeyBindsScreen(this, options)))
                .tooltip(Tooltip.create(t("keys.open.tooltip"))).build();
        list.addBig(keys);
    }

    @Override
    public void removed() {
        super.removed();
        mod.config().save();
    }
}
