package me.exz.omniocular.command;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import me.exz.omniocular.handler.UpstreamConfigHandler;
import me.exz.omniocular.handler.XMLConfigEventHandler;
import me.exz.omniocular.network.XMLConfigMessageHandler;
import me.exz.omniocular.util.LogHelper;

public class CommandReloadConfig extends CommandBase {

    @Override
    public String getCommandName() {
        return "oor";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 3;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return MinecraftServer.getServer()
            .isSinglePlayer() || super.canCommandSenderUseCommand(sender);
    }

    @Override
    public String getCommandUsage(ICommandSender iCommandSender) {
        return "/oor [update]";
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1 && "update".startsWith(args[0])) {
            return Arrays.asList("update");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] array) {
        if (array.length >= 1 && array[0].equals("update")) {
            startUpdate(sender);
            return;
        }
        reloadAndPush(sender);
    }

    /** 把磁盘上的本地 XML 重新合并并推给所有在线玩家（本命令原本的行为）。 */
    private void reloadAndPush(ICommandSender sender) {
        int pushed = XMLConfigMessageHandler.pushMergedConfigToAllPlayers();
        if (pushed < 0) {
            // 合并结果为空：下发会把客户端的本地规则清空，警告由推送方法记录
            return;
        }
        LogHelper.info(sender.getCommandSenderName() + " commit a config reload (" + pushed + " player(s)).");
    }

    /**
     * {@code /oor update}：从上游仓库拉取配置覆盖本地，完成后推给在线玩家。
     *
     * <p>
     * 与客户端 {@code /oo update} 的唯一区别就是最后这次推送：服务端更新完配置后，玩家手里
     * 还是登录时收到的那份旧规则，得重新下发（否则只能靠重启服务器或让玩家重登）。
     * 更新在后台线程跑，推送由 {@link XMLConfigEventHandler#requestPush()} 调度回服务端线程。
     */
    private void startUpdate(ICommandSender sender) {
        if (UpstreamConfigHandler.isUpdating()) {
            sender.addChatMessage(new ChatComponentTranslation("omniocular.info.UpdateRunning"));
            return;
        }
        sender.addChatMessage(new ChatComponentTranslation("omniocular.info.UpdateStarted"));
        LogHelper.info(sender.getCommandSenderName() + " requested an upstream config update.");
        UpstreamConfigHandler.update(true, new Runnable() {

            @Override
            public void run() {
                XMLConfigEventHandler.requestPush();
            }
        });
    }
}
