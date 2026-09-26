package me.exz.omniocular.handler;

import net.minecraft.server.MinecraftServer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.network.XMLConfigMessageHandler;

public class XMLConfigEventHandler {

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
