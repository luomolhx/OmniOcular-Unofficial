package me.exz.omniocular.proxy;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLInterModComms;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.relauncher.Side;
import me.exz.omniocular.command.CommandReloadConfig;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.handler.UpstreamConfigHandler;
import me.exz.omniocular.handler.XMLConfigEventHandler;
import me.exz.omniocular.handler.XMLConfigHandler;
import me.exz.omniocular.network.XMLConfigMessage;
import me.exz.omniocular.network.XMLConfigMessageHandler;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.initConfig(event);
        XMLConfigHandler.initConfigFiles(event);
        XMLConfigMessageHandler.network
            .registerMessage(XMLConfigMessageHandler.class, XMLConfigMessage.class, 0, Side.CLIENT);
    }

    public void init(FMLInitializationEvent event) {
        FMLInterModComms.sendMessage("Waila", "register", "me.exz.omniocular.waila.EntityHandler.callbackRegister");

        FMLInterModComms.sendMessage("Waila", "register", "me.exz.omniocular.waila.TileEntityHandler.callbackRegister");
        FMLCommonHandler.instance()
            .bus()
            .register(new XMLConfigEventHandler());

        // 检测到 GTNH 整合包时拉取上游配置。放在 init 而不是 preInit：配置项要到
        // Config.initConfig 之后才可读；下载在后台线程，不阻塞启动。
        UpstreamConfigHandler.update(false);
    }

    /**
     * 更新结果回显。公共端只留日志（调用方已记录），服务端也没有聊天栏可发。
     * 客户端覆写见 {@code ClientProxy#notifyChat}。
     */
    public void notifyChat(String translationKey, Object... args) {}

    public void postInit(FMLPostInitializationEvent event) {

    }

    public void onServerStart(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandReloadConfig());
    }

}
