package fr.raconteur.chatlogs.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.raconteur.chatlogs.gui.NewEventMarkerScreen;
import fr.raconteur.chatlogs.i18n.I18N;
import fr.raconteur.chatlogs.session.Session;
import fr.raconteur.chatlogs.session.SessionRecorder;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
	@Shadow @Final MinecraftClient client;
	
	@Inject(method = "onKey", at = @At("RETURN"))
	private void handleKey(long window, int key, int scancode, int i, int j, CallbackInfo ci) {
		boolean isBeingPressed = i == GLFW.GLFW_PRESS;
		if(key == 'M' && Screen.hasControlDown() && isBeingPressed && SessionRecorder.current() != null) {
			if (Screen.hasAltDown()) {
				Text title = I18N.translateAsText("gui.marker.title");
				Session.Event event = new Session.Event(title, 
						System.currentTimeMillis(), DyeColor.RED.getSignColor());
				SessionRecorder.current().addEvent(event);
				this.client.inGameHud.setOverlayMessage(title, true);
				return;
			}
			
			MinecraftClient.getInstance().setScreen(new NewEventMarkerScreen());
		}
	}
}
