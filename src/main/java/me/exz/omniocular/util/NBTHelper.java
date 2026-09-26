package me.exz.omniocular.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@SuppressWarnings({ "CanBeFinal" })
public class NBTHelper {

    /**
     * 供 JS 的 {@code name(nbt)} 由 NBT 反查 ItemStack 的缓存。
     *
     * <p>
     * 按<b>权重</b>而非条目数限量：原实现是 {@code maximumSize(1000)}，但每条是一个活着的
     * NBT 子树，而 GT5U 的 ME 输出仓这类机器会把整个 ME 网络缓冲塞进单个键，
     * 于是一个根 compound 就可以很大——1000 条的保留量 = 1000 × 最大 NBT。
     *
     * <p>
     * 另注意用 {@link com.google.common.cache.Cache} 而非 LoadingCache：原先的 loader 在
     * 未命中时返回一个<b>新的空 compound</b>，使 {@code name(...)} 静默返回 "__ERROR__"
     * 而不是暴露"这里没有缓存"，排查时会被误导。
     */
    public static Cache<Integer, NBTTagCompound> NBTCache = CacheBuilder.newBuilder()
        .maximumWeight(200_000)
        .weigher((Integer key, NBTTagCompound value) -> nbtWeight(value))
        .build();

    /** 估算 NBT 树的节点数，作为缓存权重。 */
    private static int nbtWeight(NBTBase base) {
        switch (base.getId()) {
            case 9: { // NBTTagList
                int n = 1;
                for (Object child : ((NBTTagList) base).tagList) {
                    n += nbtWeight((NBTBase) child);
                }
                return n;
            }
            case 10: { // NBTTagCompound
                int n = 1;
                // tagMap 在 1.7.10 里是原始类型 Map，values() 返回 Object，
                // 需显式强转——NBTSerializer 对同一字段也是这么处理的
                for (Object child : ((NBTTagCompound) base).tagMap.values()) {
                    n += nbtWeight((NBTBase) child);
                }
                return n;
            }
            default:
                return 1;
        }
    }

    static Gson gson1 = new GsonBuilder().registerTypeHierarchyAdapter(NBTBase.class, new NBTSerializer())
        .create();

    private static final Gson gson2 = new GsonBuilder().setPrettyPrinting()
        .registerTypeHierarchyAdapter(NBTBase.class, new NBTSerializer())
        .create();

    public static String NBT2json(NBTBase n) {
        try {
            return gson1.toJson(n);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "__ERROR__";
    }

    public static String NBT2jsonPrettyPrinting(NBTBase n) {
        try {
            return gson2.toJson(n);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "__ERROR__";
    }

    public static String MD5(String string) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(string.getBytes());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }
}
