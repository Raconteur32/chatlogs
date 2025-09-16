package fr.raconteur.chatlogs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.session.SimpleSessionRecorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {
	@Inject(method = "connect(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;"
			+ "Lnet/minecraft/client/network/ServerInfo;Lnet/minecraft/client/network/CookieStorage;)V", at = @At("HEAD"))
	private void onConnect(MinecraftClient mc, ServerAddress addr, ServerInfo info, CookieStorage cs, CallbackInfo ci) {
		// Always use server address as session name
		String serverAddress = addr != null ? addr.toString() : "Unknown Server";
		ChatLogsMod.LOGGER.info("Connecting to server '{}' - beginning chat log session", serverAddress);
		SimpleSessionRecorder.start(serverAddress, true); // true = multiplayer
	}
}