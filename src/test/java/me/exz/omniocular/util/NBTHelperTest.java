package me.exz.omniocular.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.junit.Test;

/**
 * 覆盖 NBT → JSON 的序列化契约。
 *
 * <p>
 * 这段输出会被 {@code engine.eval("var nbt=" + json)} 当作 JS 对象字面量求值，
 * 所有 XML 规则都通过 {@code nbt['key']} 读取它，所以 JSON 的形状是**隐式公开契约**：
 * 类型变了或少了注入字段，规则会静默失效（结果被当作 undefined 过滤掉）。
 */
public class NBTHelperTest {

    @Test
    public void serializesNumericAndStringTags() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("i", 42);
        nbt.setString("s", "hi");
        nbt.setDouble("d", 1.5);

        String json = NBTHelper.NBT2json(nbt);

        assertTrue(json, json.contains("\"i\":42"));
        assertTrue(json, json.contains("\"s\":\"hi\""));
        assertTrue(json, json.contains("\"d\":1.5"));
    }

    /**
     * 布尔在 NBT 里存为 NBTTagByte，序列化后是数字 1/0 而不是 JSON 的 true/false。
     *
     * <p>
     * 脚本里大量写 {@code nbt['mActive']==1}，依赖的正是"1 而不是 true"。
     */
    @Test
    public void serializesBooleanAsByteNumber() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setBoolean("t", true);
        nbt.setBoolean("f", false);

        String json = NBTHelper.NBT2json(nbt);

        assertTrue(json, json.contains("\"t\":1"));
        assertTrue(json, json.contains("\"f\":0"));
    }

    /**
     * 每个 compound（含嵌套）都要注入 {@code hashCode} 字段。
     *
     * <p>
     * JS 的 {@code name(nbt['mOutputItem0'])} 靠这个字段反查物品名
     * （{@code JSEngine.getDisplayName(n.hashCode)}）。它属隐式契约：
     * 一旦不再注入，所有调用 {@code name()} 的规则会静默变成 "__ERROR__" 并被过滤。
     */
    @Test
    public void injectsHashCodeIntoNestedCompounds() {
        NBTTagCompound inner = new NBTTagCompound();
        inner.setString("Name", "x");

        NBTTagCompound outer = new NBTTagCompound();
        outer.setTag("item", inner);

        String json = NBTHelper.NBT2json(outer);

        // 外层与内层各一个
        int first = json.indexOf("\"hashCode\"");
        assertTrue("外层 compound 应注入 hashCode: " + json, first >= 0);
        assertTrue("嵌套 compound 也应注入 hashCode: " + json, json.indexOf("\"hashCode\"", first + 1) > first);
    }

    @Test
    public void serializesListAsArray() {
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagString("a"));
        list.appendTag(new NBTTagString("b"));

        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag("l", list);

        String json = NBTHelper.NBT2json(nbt);

        assertTrue(json, json.contains("[\"a\",\"b\"]"));
    }

    /**
     * {@code NBTHelper.MD5} 用于生成脚本函数的标识（{@code scriptSet} 的键）。
     * 用已知向量固定住实现，含 {@code %02x} 补零——少了补零会让不同的脚本碰撞到同一个键。
     */
    @Test
    public void md5MatchesKnownVectorAndZeroPads() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", NBTHelper.MD5("abc"));
        // 内容含前导零字节的情形：MD5("") = d41d8cd98f00b204e9800998ecf8427e，含 "0"
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", NBTHelper.MD5(""));
        assertEquals(
            32,
            NBTHelper.MD5("anything")
                .length());
    }
}
