package me.exz.omniocular.client.command;

import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MovingObjectPosition;

import mcp.mobius.waila.api.impl.DataAccessorCommon;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.handler.UpstreamConfigHandler;
import me.exz.omniocular.util.NBTHelper;
import me.exz.omniocular.util.Stats;

public class CommandLookFor extends CommandBase {

    @Override
    public String getCommandName() {
        return "oo";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayer;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/oo";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] array) {
        if (array.length == 1 && array[0].equals("reload")) {
            Config.preprocess();
            return;
        }
        if (array.length == 1 && array[0].equals("update")) {
            if (UpstreamConfigHandler.isUpdating()) {
                sender.addChatMessage(new ChatComponentTranslation("omniocular.info.UpdateRunning"));
                return;
            }
            sender.addChatMessage(new ChatComponentTranslation("omniocular.info.UpdateStarted"));
            // 忽略 GTNH 检测与开关：这是手动触发，开发环境里也得能跑
            UpstreamConfigHandler.update(true);
            return;
        }
        if (array.length >= 1 && array[0].equals("stats")) {
            printStats(sender, array);
            return;
        }
        boolean displayNBT = (array.length == 1 && array[0].equals("nbt"));

        EntityPlayer player = (EntityPlayer) sender;
        Minecraft minecraft = Minecraft.getMinecraft();
        MovingObjectPosition objectMouseOver = minecraft.objectMouseOver;

        switch (objectMouseOver.typeOfHit) {
            case ENTITY: {
                Class<?> pointEntityClass = objectMouseOver.entityHit.getClass();
                if (EntityList.classToStringMapping.containsKey(pointEntityClass)) {
                    player.addChatComponentMessage(
                        new ChatComponentText(EntityList.getEntityString(objectMouseOver.entityHit)));
                }
                break;
            }

            case BLOCK: {
                Block block = DataAccessorCommon.instance.getBlock();

                player.addChatComponentMessage(
                    new ChatComponentTranslation(
                        "Name: %s (%s@%s) Class: %s",
                        DataAccessorCommon.instance.getBlockQualifiedName(),
                        Block.blockRegistry.getNameForObject(block),
                        DataAccessorCommon.instance.getMetadata(),
                        block.getClass()
                            .getName()));
                player.addChatComponentMessage(
                    new ChatComponentTranslation(
                        "ItemStack: %s (%s@%s)",
                        DataAccessorCommon.instance.stack.getDisplayName(),
                        Item.getIdFromItem(DataAccessorCommon.instance.stack.getItem()),
                        DataAccessorCommon.instance.stack.getItemDamage()));

                if (displayNBT) {
                    NBTTagCompound nbtTagCompound = DataAccessorCommon.instance.getNBTData();
                    if (nbtTagCompound != null) {
                        player.addChatComponentMessage(
                            new ChatComponentTranslation(
                                "TE class: %s(%s)",
                                DataAccessorCommon.instance.getTileEntity()
                                    .getClass()
                                    .getName(),
                                DataAccessorCommon.instance.getTileEntity()
                                    .getBlockMetadata()));
                        String[] lines = NBTHelper.NBT2jsonPrettyPrinting(nbtTagCompound)
                            .split("\n");
                        player.addChatComponentMessage(new ChatComponentText("NBT:"));
                        for (String line : lines) {
                            player.addChatComponentMessage(new ChatComponentText(line));
                        }
                    }
                }
                break;
            }
            case MISS:
                player.addChatComponentMessage(new ChatComponentTranslation("omniocular.info.NotPointing"));

        }

    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            if ("reload".startsWith(args[0])) return Arrays.asList("reload");
            else if ("nbt".startsWith(args[0])) return Arrays.asList("nbt");
            else if ("stats".startsWith(args[0])) return Arrays.asList("stats");
            else if ("update".startsWith(args[0])) return Arrays.asList("update");
        }
        if (args.length == 2 && args[0].equals("stats") && "reset".startsWith(args[1])) {
            return Arrays.asList("reset");
        }

        return Arrays.asList("reload", "nbt", "stats", "update");

    }

    /**
     * {@code /oo stats} 与 {@code /oo stats reset}。
     *
     * <p>
     * 统计只有在配置里打开 {@code debug} 后才会计数；未打开时明确提示而不是显示一堆 0，
     * 否则会让人误以为"性能很好"。
     */
    private void printStats(ICommandSender sender, String[] array) {
        if (array.length == 2 && array[1].equals("reset")) {
            Stats.reset();
            sender.addChatMessage(new ChatComponentTranslation("omniocular.info.StatsReset"));
            return;
        }
        if (!Stats.enabled) {
            sender.addChatMessage(new ChatComponentTranslation("omniocular.info.DebugOff"));
            return;
        }
        for (String line : Stats.reportChatLines()) {
            sender.addChatMessage(new ChatComponentText(line));
        }
    }
}
