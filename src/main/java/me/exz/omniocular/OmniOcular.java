package me.exz.omniocular;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import me.exz.omniocular.proxy.CommonProxy;
import me.exz.omniocular.reference.Reference;

@SuppressWarnings({ "UnusedParameters", "UnusedDeclaration" })
@Mod(
    modid = Reference.MOD_ID,
    name = Reference.MOD_NAME,
    version = Reference.VERSION,
    guiFactory = "me.exz.omniocular.client.gui.GuiFactory",
    dependencies = "required-after:Waila;required-after:NotEnoughItems")

public class OmniOcular {

    @SidedProxy(clientSide = Reference.CLIENT_PROXY_CLASS, serverSide = Reference.SERVER_PROXY_CLASS)
    private static CommonProxy proxy;

    /**
     * 供后台线程（配置更新器）回显结果用。
     *
     * <p>
     * 必须走 proxy：客户端才有聊天栏，而 {@code net.minecraft.client.Minecraft} 这类类
     * 不能出现在公共代码里——服务端的 jar 没有它们，类校验时会直接 NoClassDefFoundError。
     */
    public static CommonProxy getProxy() {
        return proxy;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    void onServerStart(FMLServerStartingEvent event) {
        proxy.onServerStart(event);
    }

}
