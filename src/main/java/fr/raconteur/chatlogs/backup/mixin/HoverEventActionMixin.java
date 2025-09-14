package fr.raconteur.chatlogs.backup.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fr.raconteur.chatlogs.ChatLogsMod;
import net.minecraft.text.HoverEvent;

@Mixin(HoverEvent.Action.class)
public class HoverEventActionMixin {
	@Inject(method = "isParsable", at = @At("HEAD"), cancellable = true)
	void fuckOjang(CallbackInfoReturnable<Boolean> cir) {
		if(ChatLogsMod.PERMISSIVE_EVENTS.get()) {
			cir.setReturnValue(true);
			cir.cancel();
		}
	}
}
