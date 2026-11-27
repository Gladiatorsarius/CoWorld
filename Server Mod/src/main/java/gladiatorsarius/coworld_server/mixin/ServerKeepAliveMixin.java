package gladiatorsarius.coworld_server.mixin;

import gladiatorsarius.coworld_server.handshake.PingProber;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public final class ServerKeepAliveMixin {
	@Inject(method = "handleKeepAlive", at = @At("HEAD"), cancellable = true)
	private void coworld$handleKeepAlive(ServerboundKeepAlivePacket packet, CallbackInfo ci) {
		if (PingProber.onServerboundKeepAlive((ServerCommonPacketListenerImpl) (Object) this, packet.getId())) {
			ci.cancel();
		}
	}
}
