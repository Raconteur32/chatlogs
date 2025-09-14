package fr.raconteur.chatlogs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.raconteur.chatlogs.gui.SessionListScreen;
import fr.raconteur.chatlogs.i18n.I18N;
import fr.raconteur.chatlogs.session.UnsavedChatlogRecovery;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void onInit(CallbackInfo ci) {
		this.addDrawableChild(ButtonWidget.builder(I18N.translateAsText("gui.chatlogs"), 
						(btn) -> this.client.setScreen(new SessionListScreen()))
				.dimensions(this.width / 2 - 100, (this.height / 4 + 48) + 92 + 12, 98, 20)
				.build());
		UnsavedChatlogRecovery.tryRestoreUnsaved();
	}
}
