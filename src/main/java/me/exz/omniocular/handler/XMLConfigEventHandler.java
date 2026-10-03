package me.exz.omniocular.handler;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.MinecraftServer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.network.XMLConfigMessageHandler;
import me.exz.omniocular.util.LogHelper;

public class XMLConfigEventHandler {

    /**
     * 待推送标志：{@code /oor update} 的更新在后台线程跑完，推送要回到服务端线程再做——
     * {@code ServerConfigurationManager#playerEntityList} 会随登录/登出并发变化。
     */
    private static final AtomicBoolean pendingPush = new AtomicBoolean();

    /** 请求把更新后的配置推给所有在线玩家；线程安全，可在任意线程调用。 */
    public static void requestPush() {
        pendingPush.set(true);
    }

    /** 处理 {@link #requestPush()} 的请求；服务端 tick 保证回调在服务端线程上。 */
    @SubscribeEvent
    public void ServerTickEvent(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !pendingPush.compareAndSet(true, false)) {
            return;
        }
        int pushed = XMLConfigMessageHandler.pushMergedConfigToAllPlayers();
        if (pushed >= 0) {
            LogHelper.info("Pushed the updated OmniOcular config to " + pushed + " player(s).");
        }
    }

    @SubscribeEvent
    public void PlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
        // hasRules()：服务端配置目录为空时 mergedConfig 就是 "<root></root>"，
        // 下发它会让客户端 parseConfigFiles() 清空自己的本地规则
        if (MinecraftServer.getServer()
            .isDedicatedServer() && Config.sendToClientXML
            && XMLConfigHandler.hasRules())
            XMLConfigMessageHandler.sendConfigString(
                XMLConfigHandler.mergedConfig,
                (net.minecraft.entity.player.EntityPlayerMP) event.player);

    }
}
