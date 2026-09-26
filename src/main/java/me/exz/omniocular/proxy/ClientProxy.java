package me.exz.omniocular.proxy;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.client.ClientCommandHandler;

import codechicken.nei.guihook.GuiContainerManager;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import me.exz.omniocular.client.command.CommandLookFor;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.handler.ScriptEngineHandler;
import me.exz.omniocular.handler.XMLConfigHandler;
import me.exz.omniocular.util.LogHelper;
import me.exz.omniocular.waila.PluginEngine;
import me.exz.omniocular.waila.TooltipHandler;

@SuppressWarnings("UnusedDeclaration")
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        ScriptEngineHandler.initScriptEngineManager();
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientCommandHandler.instance.registerCommand(new CommandLookFor());
        try {
            XMLConfigHandler.releasePreConfigFiles();
        } catch (Exception e) {
            LogHelper.error("Can't release pre-config files");
            e.printStackTrace();
        }
        XMLConfigHandler.mergeConfig();

    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        GuiContainerManager.addTooltipHandler(new TooltipHandler());
        XMLConfigHandler.parseConfigFiles();
        PluginEngine.init();
        Config.preprocess();
    }

    /** 配置更新的结果回显到聊天栏。调用方在后台线程，故先排进客户端任务队列。 */
    @Override
    public void notifyChat(final String translationKey, final Object... args) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        // func_152344_a(Runnable) —— 即 addScheduledTask。GTNH 用的 MCP 集没有映射这两个
        // 重载（javap 实测只有 func_152343_a/func_152344_a），只能按 SRG 名调用。
        minecraft.func_152344_a(new Runnable() {

            @Override
            public void run() {
                EntityPlayer player = minecraft.thePlayer;
                // 启动期的自动更新常常早于进入世界：那时没有聊天栏，日志里已有记录
                if (player != null) {
                    player.addChatMessage(new ChatComponentTranslation(translationKey, args));
                }
            }
        });
    }

}
