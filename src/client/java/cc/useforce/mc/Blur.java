package cc.useforce.mc;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Screen's blurred background is protected: Force's views hand it over ({@link AppScreen#background}). */
interface Blur {
    void blur(GuiGraphicsExtractor g);
}
