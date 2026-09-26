package me.exz.omniocular.waila;

import static me.exz.omniocular.util.NBTHelper.NBTCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import me.exz.omniocular.handler.ScriptEngineHandler;
import me.exz.omniocular.handler.XMLConfigHandler;
import me.exz.omniocular.util.LogHelper;
import me.exz.omniocular.util.NBTHelper;
import me.exz.omniocular.util.Stats;

@SuppressWarnings({ "CanBeFinal", "UnusedDeclaration" })
public class JSEngine {

    public static ScriptEngine engine;
    public static HashSet<String> scriptSet = new HashSet<>();

    /**
     * 缓存条目存活时长。WAILA 自身以 250 ms 节流 NBT 同步（MetaDataProvider 的
     * isTimeElapsed(250)），缓存内容不可能比这更快地变化；取同一量级可让
     * holding() / armor() / isInHotbar() / isInInv() 这类依赖玩家状态的行
     * 不再被永久冻结，同时把哈希碰撞的误命中寿命从"直到 LRU 淘汰"压到 250 ms。
     */
    static final long CACHE_EXPIRE_MS = 250L;

    /** 每次配置重载自增，使旧配置算出的条目永不再命中（防未来漏调 invalidateAll）。 */
    private static int cacheGeneration;

    /**
     * 渲染缓存键。必须包含 patternMap 身份与 id：
     * tooltip 路径的 id 来自物品注册表而非 NBT，两个 NBT 相同的不同物品此前会互相串味。
     * FMP 与 TileEntity 有意共用 tileEntityPattern，故共用同一 mapTag。
     */
    static final class CacheKey {

        private final int generation;
        private final int mapTag;
        private final int nbtHash;
        private final String id;

        CacheKey(int generation, int mapTag, int nbtHash, String id) {
            this.generation = generation;
            this.mapTag = mapTag;
            this.nbtHash = nbtHash;
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey k = (CacheKey) o;
            return generation == k.generation && mapTag == k.mapTag && nbtHash == k.nbtHash && Objects.equals(id, k.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(generation, mapTag, nbtHash, id);
        }
    }

    /** patternMap → 稳定标签。 */
    private static int patternTag(Map<Pattern, Node> m) {
        if (m == XMLConfigHandler.tooltipPattern) return 2;
        if (m == XMLConfigHandler.entityPattern) return 1;
        return 0;
    }

    static Cache<CacheKey, List<String>> cache = CacheBuilder.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(CACHE_EXPIRE_MS, TimeUnit.MILLISECONDS)
        .build();

    private static final Map<String, String> fluidList = new HashMap<>();
    private static EntityPlayer entityPlayer;

    /**
     * 保护 {@code engine} 与 {@code scriptSet} 的一致性，兼作 Nashorn 引擎的互斥锁。
     *
     * <p>
     * 渲染线程在 {@link #getBody} 里执行脚本，而配置重载（服务端下发配置、{@code /oor}）
     * 是在<b>网络线程</b>调用 {@link #initEngine} 替换 engine 并清空 scriptSet。
     * Nashorn 的 {@code ScriptEngine} 不是线程安全的；而且 scriptSet 必须与 engine 同代——
     * 否则脚本哈希仍在集合里、新引擎上却没有对应函数，{@code invokeFunction} 抛
     * NoSuchMethodException 后被内层 catch 吞掉，表现为该行静默消失。
     */
    public static final Object ENGINE_LOCK = new Object();

    static List<String> getBody(Map<Pattern, Node> patternMap, NBTTagCompound n, String id, EntityPlayer player) {
        entityPlayer = player;
        int mapTag = patternTag(patternMap);
        // n.hashCode() 是内容哈希（NBTTagCompound 覆写为 类型id ^ tagMap.hashCode()，
        // 各叶子类型也都按内容覆写），与原 n.toString().hashCode() 语义等价，
        // 但不必递归构建整个 NBT 的字符串。
        CacheKey key = new CacheKey(cacheGeneration, mapTag, n.hashCode(), id);

        Stats.getBodyCall();

        List<String> cached = cache.getIfPresent(key);
        if (cached != null) {
            Stats.cacheHit();
            return cached;
        }
        Stats.cacheMiss();
        Stats.recordNbt(id, n);

        List<String> tips = new ArrayList<>();

        // 整个脚本执行段持锁：见 ENGINE_LOCK 的说明。锁只在配置重载时被争用。
        synchronized (ENGINE_LOCK) {
            try {
                long t = Stats.begin();
                String json = "var nbt=" + NBTHelper.NBT2json(n) + ";";
                engine.eval(json);
                Stats.endEval(t);
                Stats.jsonChars(json.length());
            } catch (ScriptException e) {
                e.printStackTrace();
            }

            for (Map.Entry<Pattern, Node> entry : patternMap.entrySet()) {
                Matcher matcher = entry.getKey()
                    .matcher(id);
                if (matcher.matches()) {
                    Element item = (Element) entry.getValue();
                    // if (item.getElementsByTagName("head").getLength() > 0) {
                    // Node head = item.getElementsByTagName("head").item(0);
                    // }
                    if (item.getElementsByTagName("line")
                        .getLength() > 0) {
                        String tip;
                        NodeList lines = item.getElementsByTagName("line");
                        for (int i = 0; i < lines.getLength(); i++) {
                            Node line = lines.item(i);
                            String displayname = "";
                            Node display = line.getAttributes()
                                .getNamedItem("displayname");
                            if (display != null && !display.getTextContent()
                                .trim()
                                .isEmpty()) {
                                displayname = StatCollector.translateToLocal(display.getTextContent());
                            }
                            String functionContent = line.getTextContent();
                            String hash = "S" + NBTHelper.MD5(functionContent);
                            if (!JSEngine.scriptSet.contains(hash)) {
                                JSEngine.scriptSet.add(hash);
                                if (!functionContent.contains("return")) {
                                    functionContent = "return " + functionContent.trim();
                                }
                                String script = "function " + hash + "()" + "{" + functionContent + "}";
                                try {
                                    JSEngine.engine.eval(script);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                            Invocable invoke = (Invocable) JSEngine.engine;
                            try {
                                long tInvoke = Stats.begin();
                                String result = String.valueOf(invoke.invokeFunction(hash, ""));
                                Stats.endInvoke(tInvoke);
                                if (result.equals("__ERROR__") || result.equals("null")
                                    || result.equals("undefined")
                                    || result.equals("NaN")) {
                                    continue;
                                }
                                if (mapTag == 2) {
                                    tip = "§7" + displayname + ": §f";
                                } else {
                                    // getDisplaynameTemplate() 保证非 null：setting 缺失时原先在
                                    // 此抛 NPE 并被下方 catch 吞掉，导致所有方块/实体行静默消失
                                    tip = XMLConfigHandler.getDisplaynameTemplate()
                                        .replace("DISPLAYNAME", displayname)
                                        .replace("RETURN", result);
                                }
                            } catch (Exception e) {
                                continue;
                                // e.printStackTrace();
                            }
                            if (tip.equals("__ERROR__")) {
                                continue;
                            }
                            tips.addAll(Arrays.asList(tip.split("\n")));
                        }
                    }
                }
            }
        }
        cache.put(key, tips);
        return tips;
    }

    public static void initEngine() {
        synchronized (ENGINE_LOCK) {
            scriptSet.clear();
            // 配置重载必须让旧条目失效：否则服务器下发新配置或 /oor 之后，
            // 同一个 NBT 仍会返回按旧规则算出的行。
            cache.invalidateAll();
            cacheGeneration++;
            // 不同规则集的统计数据不应混在一起
            Stats.onConfigReload();

            engine = ScriptEngineHandler.manager.getEngineByName("js");

            if (engine == null) {
                LogHelper.fatal("no javascript engine.");
                throw new RuntimeException("no javascript engine.");
            }

            setSpecialChar();

            try {
                engine.eval("load(\"nashorn:mozilla_compat.js\");");
            } catch (ScriptException ignored) {}
            try {
                engine.eval("var _JSEngine = Java.type('me.exz.omniocular.waila.JSEngine');");
                engine.eval("function translate(t){return _JSEngine.translate(t)}");
                engine.eval("function translateFormatted(t,obj){return _JSEngine.translateFormatted(t,obj)}");
                engine.eval("function name(n){return _JSEngine.getDisplayName(n.hashCode)}");
                engine.eval("function fluidName(n){return _JSEngine.getFluidName(n)}");
                engine.eval("function holding(){return _JSEngine.playerHolding()}");
                engine.eval("function armor(i){return _JSEngine.playerArmor(i)}");
                engine.eval("function isInHotbar(n){return _JSEngine.haveItemInHotbar(n)}");
                engine.eval("function isInInv(n){return _JSEngine.haveItemInInventory(n)}");
            } catch (ScriptException e) {
                e.printStackTrace();
            }
        }

    }

    private static void setSpecialChar() {
        String MCStyle = "§";
        engine.put("BLACK", MCStyle + "0");
        engine.put("DBLUE", MCStyle + "1");
        engine.put("DGREEN", MCStyle + "2");
        engine.put("DAQUA", MCStyle + "3");
        engine.put("DRED", MCStyle + "4");
        engine.put("DPURPLE", MCStyle + "5");
        engine.put("GOLD", MCStyle + "6");
        engine.put("GRAY", MCStyle + "7");
        engine.put("DGRAY", MCStyle + "8");
        engine.put("BLUE", MCStyle + "9");
        engine.put("GREEN", MCStyle + "a");
        engine.put("AQUA", MCStyle + "b");
        engine.put("RED", MCStyle + "c");
        engine.put("LPURPLE", MCStyle + "d");
        engine.put("YELLOW", MCStyle + "e");
        engine.put("WHITE", MCStyle + "f");

        engine.put("OBF", MCStyle + "k");
        engine.put("BOLD", MCStyle + "l");
        engine.put("STRIKE", MCStyle + "m");
        engine.put("UNDER", MCStyle + "n");
        engine.put("ITALIC", MCStyle + "o");
        engine.put("RESET", MCStyle + "r");
        String WailaStyle = "¤";
        String WailaIcon = "¥";
        engine.put("TAB", WailaStyle + WailaStyle + "a");
        engine.put("ALIGNRIGHT", WailaStyle + WailaStyle + "b");
        engine.put("ALIGNCENTER", WailaStyle + WailaStyle + "c");
        engine.put("HEART", WailaStyle + WailaIcon + "a");
        engine.put("HHEART", WailaStyle + WailaIcon + "b");
        engine.put("EHEART", WailaStyle + WailaIcon + "c");
        // LogHelper.info("Special Char loaded");
    }

    public static String translate(String t) {
        return StatCollector.translateToLocal(t);
    }

    public static String translateFormatted(String t, Object[] format) {
        return StatCollector.translateToLocalFormatted(t, format);
    }

    public static String playerHolding() {
        ItemStack is = entityPlayer.getHeldItem();
        if (is == null) {
            return "";
        }
        return Item.itemRegistry.getNameForObject(is.getItem());
    }

    public static String playerArmor(int i) {
        if (i < 0 || i > 3) {
            return null;
        }
        ItemStack is = entityPlayer.inventory.armorInventory[i];
        if (is == null) {
            return "";
        }
        return Item.itemRegistry.getNameForObject(is.getItem());
    }

    public static Boolean haveItemInHotbar(String n) {
        int s = entityPlayer.inventory.func_146029_c((Item) Item.itemRegistry.getObject(n));
        return s > -1 && s < 9;
    }

    public static Boolean haveItemInInventory(String n) {
        return entityPlayer.inventory.func_146029_c((Item) Item.itemRegistry.getObject(n)) != -1;
    }

    public static String getDisplayName(String hashCode) {
        try {
            NBTTagCompound nc = NBTCache.getIfPresent(Integer.valueOf(hashCode));
            if (nc == null) {
                // 条目已被权重淘汰（或从未写入）。返回 __ERROR__ 会被 getBody 过滤掉，
                // 与原行为一致，但不再凭空构造一个空 compound 来掩盖"缓存里没有"这件事。
                return "__ERROR__";
            }
            ItemStack is = ItemStack.loadItemStackFromNBT(nc);
            return is.getDisplayName();
        } catch (Exception e) {
            return "__ERROR__";
        }
    }

    public static String getFluidName(String uName) {
        if (fluidList.containsKey(uName)) {
            return fluidList.get(uName);
        } else {
            try {
                Fluid f = FluidRegistry.getFluid(uName.toLowerCase());
                FluidStack fs = new FluidStack(f, 1);
                String lName = fs.getLocalizedName();
                fluidList.put(uName, lName);
                return lName;
            } catch (Exception e) {
                return "__ERROR__";
            }
        }
    }
}
