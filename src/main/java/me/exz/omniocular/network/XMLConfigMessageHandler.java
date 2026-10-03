package me.exz.omniocular.network;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.handler.XMLConfigHandler;
import me.exz.omniocular.reference.Reference;
import me.exz.omniocular.util.LogHelper;

public class XMLConfigMessageHandler implements IMessageHandler<XMLConfigMessage, IMessage> {

    public static final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(Reference.MOD_ID);

    @Override
    public IMessage onMessage(XMLConfigMessage message, MessageContext ctx) {
        // LogHelper.info("Config Received: "+ message.text);
        recvConfigString(message.text);
        return null;
    }

    private static void sendString(String string, EntityPlayerMP player) {
        XMLConfigMessageHandler.network.sendTo(new XMLConfigMessage(string), player);
    }

    public static void sendConfigString(String string, EntityPlayerMP player) {
        sendString("__START__", player);
        int size = 10240;
        while (string.length() > size) {
            sendString(string.substring(0, size), player);
            string = string.substring(size);
        }
        if (!string.isEmpty()) {
            sendString(string, player);
        }
        sendString("__END__", player);
    }

    /**
     * 重新合并本地配置并推给所有在线玩家。空配置不推——客户端收到会清空本地规则
     * （见 {@link #recvConfigString} 的 {@code __END__} 分支）。
     *
     * <p>
     * <b>必须在服务端线程调用</b>：{@code playerEntityList} 会随玩家登录/登出并发变化。
     *
     * @return 收到配置的玩家数；因合并结果为空而未推送时返回 -1
     */
    public static int pushMergedConfigToAllPlayers() {
        XMLConfigHandler.mergeConfig();
        if (!XMLConfigHandler.hasRules()) {
            LogHelper.warn("Merged config is empty; not sending it (it would wipe clients' local rules).");
            return -1;
        }
        String config = XMLConfigHandler.mergedConfig;
        int sent = 0;
        for (EntityPlayerMP player : (List<EntityPlayerMP>) MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            sendConfigString(config, player);
            sent++;
        }
        return sent;
    }

    /** 累计接收上限，防止异常或恶意的服务端用无限分片撑爆客户端内存。 */
    private static final int MAX_CONFIG_CHARS = 8 * 1024 * 1024;

    /**
     * 接收端状态机。原先用 switch 直接 append，没有 __START__ 时
     * {@code stringBuilder} 为 null → NPE；且接收累计长度无上限。
     * 现在乱序/缺失标记一律丢弃而非抛异常，并对总长度设限。
     */
    private static void recvConfigString(String string) {
        if (Config.forceUseClientXml) return;

        if ("__START__".equals(string)) {
            XMLConfigHandler.stringBuilder = new StringBuilder();
            return;
        }

        if ("__END__".equals(string)) {
            StringBuilder sb = XMLConfigHandler.stringBuilder;
            XMLConfigHandler.stringBuilder = null;
            if (sb == null) {
                LogHelper.warn("Received end-of-config without a matching start marker; discarded.");
                return;
            }
            final String received = sb.toString();
            // 旧服务端在配置目录为空时会下发 "<root></root>"；接受它等于清空本地规则，
            // 所以宁可保留客户端自己的配置
            if (!XMLConfigHandler.hasRules(received)) {
                LogHelper.warn("Received an empty server config; keeping the local rules.");
                return;
            }
            XMLConfigHandler.mergedConfig = received;
            // 只记长度：整份配置可达数百 KB，原先把全文写进日志会淹没日志文件
            LogHelper.info("Received server config (" + received.length() + " chars)");
            XMLConfigHandler.parseConfigFiles();
            return;
        }

        StringBuilder sb = XMLConfigHandler.stringBuilder;
        if (sb == null) {
            // 未收到 __START__，或上一份配置已结束——按乱序处理并丢弃
            return;
        }
        if (sb.length() + string.length() > MAX_CONFIG_CHARS) {
            LogHelper.error("Server config exceeds " + MAX_CONFIG_CHARS + " chars; discarding the remainder.");
            XMLConfigHandler.stringBuilder = null;
            return;
        }
        sb.append(string);
    }
}
