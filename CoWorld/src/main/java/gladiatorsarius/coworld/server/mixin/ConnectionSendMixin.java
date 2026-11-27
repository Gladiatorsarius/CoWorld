package gladiatorsarius.coworld.server.mixin;

import gladiatorsarius.coworld.server.handshake.PingProber;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public final class ConnectionSendMixin {
	@Inject(method = "doSendPacket", at = @At("HEAD"))
	private void coworld$doSendPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush,
									  CallbackInfo ci) {
		PingProber.onPacketSent(packet);
	}
}
