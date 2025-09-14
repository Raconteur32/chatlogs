package fr.raconteur.chatlogs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import fr.raconteur.chatlogs.config.Options;
import net.minecraft.client.gui.hud.ChatHud;

@Mixin(value = ChatHud.class, priority = 408)
public class ChaHudMixin_overrideMaxMessages {
	@ModifyConstant(
			method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;"
					+ "ILnet/minecraft/client/gui/hud/MessageIndicator;Z)V", 
			constant = @Constant(intValue = 100), 
			require = 0
	)
	private int overrideMaxMessages(int initial) {
		return Options.visibleLineCount;
	}
}
