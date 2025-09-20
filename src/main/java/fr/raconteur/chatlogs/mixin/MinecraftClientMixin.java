package fr.raconteur.chatlogs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.session.SimpleSessionRecorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.SaveLoader;
import net.minecraft.world.level.storage.LevelStorage;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	@Inject(
			method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V", 
			at = @At(value = "HEAD")
	)
	private void onDisconnected(CallbackInfo ci) {
		// End current session when disconnecting from world/server
		ChatLogsMod.LOGGER.debug("Disconnecting - ending chat log session");
		SimpleSessionRecorder.end();
	}
	
	@Inject(method = "stop", at = @At(value = "INVOKE", target = "java/lang/System.exit(I)V"))
	private void onStop(CallbackInfo ci) {
		// End current session when client is stopping
		ChatLogsMod.LOGGER.info("Minecraft client stopping - ending chat log session");
		SimpleSessionRecorder.end();
	}
	
	@Inject(
			method= "startIntegratedServer", 
			at = @At(
					value = "INVOKE", 
					target = "net/minecraft/client/MinecraftClient.disconnectWithProgressScreen()V", 
					shift = At.Shift.AFTER
			)
	)
	private void onStartSingleplayer(LevelStorage.Session session, ResourcePackManager dataPackManager, 
			SaveLoader saveLoader, boolean newWorld, CallbackInfo ci) {
		// Start new session for singleplayer world
		String worldName = session.getDirectoryName();
		ChatLogsMod.LOGGER.info("Starting singleplayer world '{}' - beginning chat log session", worldName);
		SimpleSessionRecorder.start(worldName, false); // false = singleplayer
	}
}