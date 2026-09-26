package me.exz.omniocular.waila;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaEntityAccessor;
import mcp.mobius.waila.api.IWailaEntityProvider;
import mcp.mobius.waila.api.IWailaRegistrar;
import me.exz.omniocular.IScript;
import me.exz.omniocular.config.Config;
import me.exz.omniocular.handler.XMLConfigHandler;

public class EntityHandler implements IWailaEntityProvider {

    @SuppressWarnings("UnusedDeclaration")
    public static void callbackRegister(IWailaRegistrar registrar) {
        EntityHandler instance = new EntityHandler();
        // registrar.registerSyncedNBTKey("*", Entity.class);
        registrar.registerBodyProvider(instance, Entity.class);
        registrar.registerNBTProvider(instance, Entity.class);

    }

    @Override
    public Entity getWailaOverride(IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        return null;
    }

    @Override
    public List<String> getWailaHead(Entity entity, List<String> currenttip, IWailaEntityAccessor accessor,
        IWailaConfigHandler config) {
        return currenttip;
    }

    private int lastEntityId;
    private long lastTick;
    private List<String> lastTps;
    private static final List<String> EMPTY_LIST = new ArrayList<>();

    @Override
    public List<String> getWailaBody(Entity entity, List<String> currenttip, IWailaEntityAccessor accessor,
        IWailaConfigHandler config) {
        if (!Config.enableEntityInfo || Config.blackEntity.contains(
            entity.getClass()
                .getName()))
            return currenttip;

        int id = entity.getEntityId();
        long currentTick = accessor.getWorld()
            .getTotalWorldTime();
        if (id != lastEntityId || currentTick - lastTick > 10) {
            lastEntityId = id;
            lastTick = currentTick;
            NBTTagCompound n = accessor.getNBTData();
            if (n != null) {
                String entityId = resolveEntityId(entity, n);
                lastTps = PluginEngine.getWailaBody(IScript.Type.Entity, n, entityId, accessor.getPlayer());
                lastTps.addAll(JSEngine.getBody(XMLConfigHandler.entityPattern, n, entityId, accessor.getPlayer()));
            } else lastTps = EMPTY_LIST;
        }

        currenttip.addAll(lastTps);
        return currenttip;
    }

    @Override
    public List<String> getWailaTail(Entity entity, List<String> currenttip, IWailaEntityAccessor accessor,
        IWailaConfigHandler config) {
        return currenttip;
    }

    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, Entity ent, NBTTagCompound tag, World world) {
        if (ent != null) {
            ent.writeToNBT(tag);
            // 服务端也写一份，使发往客户端的 tag 自带 id，Java 脚本可直接读到
            String id = EntityList.getEntityString(ent);
            if (id != null) {
                tag.setString("id", id);
            }
        }
        return tag;

    }

    /**
     * 解析实体的脚本匹配 id。
     *
     * <p>
     * WAILA 与 vanilla 都不会给实体 NBT 写入 "id"：{@code Entity.writeToNBT} 不写，
     * 写 "id" 的 {@code writeMountToNBT} / {@code writeToNBTOptional} 都不是这条路径调用的；
     * WAILA 的 {@code Message0x03EntRequest} 也不补（对比 TERequest 会写 classToNameMap）。
     * 所以 {@code n.getString("id")} 恒为空串，而 XML 的 &lt;entity id="..."&gt; 模式是全串匹配，
     * 永远无法命中——CCYF78 语料里 16 条实体规则（含 Galacticraft 火箭倒计时）因此全部失效。
     *
     * <p>
     * 这里在客户端由 entity 实例反查注册名补上。之所以放在客户端而非只放在
     * {@link #getNBTData}：单机（无服务端）时 WAILA 会绕过 NBT provider 直接调
     * {@code writeToNBT}，只在服务端补会漏掉单机路径。
     *
     * @return 匹配用 id；无法解析时返回空串（此时不会有任何规则命中，与旧行为一致）
     */
    private static String resolveEntityId(Entity entity, NBTTagCompound nbt) {
        String id = nbt.getString("id");
        if (!id.isEmpty()) {
            return id;
        }
        id = EntityList.getEntityString(entity);
        if (id == null) {
            return "";
        }
        // 写回 NBT，使该实体的渲染缓存键稳定，且 Java 脚本也能读到同一个 id
        nbt.setString("id", id);
        return id;
    }
}
