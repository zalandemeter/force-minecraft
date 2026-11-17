package cc.useforce.mc;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mods → Force → Configure (Mod Menu, which Fabulously Optimized ships; without it this class is never loaded). */
public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new SettingsScreen(parent, ForceClient.instance());
    }
}
