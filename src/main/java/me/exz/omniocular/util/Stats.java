package me.exz.omniocular.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 性能埋点。
 *
 * <p>
 * 仅在 {@code Config.debug} 打开时计数：关闭时每个入口只多一次 static boolean 读取，
 * 不分配、不装箱、不访问 Map。多数计数器直接读写非 final 的 static 字段；只有
 * {@link #begin()} 会调用 {@code System.nanoTime()}，且由调用方先判 enabled。
 *
 * <p>
 * <b>测量本身有代价</b>：{@link #recordNbt} 会把 NBT 序列化两遍（未压缩 + gzip）以计量
 * 真实体积，这比正常渲染路径更重。所以读到的耗时是上限而非精确值——用于<b>定位</b>瓶颈
 * （哪一类 TE 的 NBT 最大、eval 与 invoke 谁占主导），不用于度量绝对性能。
 */
public class Stats {

    /** 由 Config 在加载配置时同步，不要直接改。 */
    public static boolean enabled = false;

    /** 大于 0 时，每 N 秒向日志输出一次汇总（0 = 只输出到 /oo stats）。 */
    public static int reportIntervalSeconds = 0;

    private static final int MAX_IDS = 512;
    private static final int REPORT_EVERY_CALLS = 256;
    private static final int TOP_N = 10;

    private static long getBodyCalls;
    private static long cacheHits;
    private static long cacheMisses;
    private static long evalCalls;
    private static long invokeCalls;
    private static long evalNanos;
    private static long invokeNanos;
    private static long jsonChars;
    private static long uncompressedBytes;
    private static long compressedBytes;

    /** TE id → {未命中次数, 未压缩字节合计, 未压缩字节最大值} */
    private static final Map<String, long[]> perId = new HashMap<>();

    private static boolean idsTruncated;
    private static long lastReportMillis;
    private static long reportCountdown = REPORT_EVERY_CALLS;

    // ------------------------------------------------------------------ 计时

    /** 成对使用：{@code long t = Stats.begin(); ... Stats.endEval(t);} */
    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void endEval(long t0) {
        if (!enabled) return;
        evalNanos += System.nanoTime() - t0;
        evalCalls++;
    }

    public static void endInvoke(long t0) {
        if (!enabled) return;
        invokeNanos += System.nanoTime() - t0;
        invokeCalls++;
    }

    // ---------------------------------------------------------------- 计数

    public static void getBodyCall() {
        if (!enabled) return;
        getBodyCalls++;
        if (reportIntervalSeconds > 0 && --reportCountdown <= 0) {
            reportCountdown = REPORT_EVERY_CALLS;
            maybeReport();
        }
    }

    public static void cacheHit() {
        if (enabled) cacheHits++;
    }

    public static void cacheMiss() {
        if (enabled) cacheMisses++;
    }

    public static void jsonChars(int n) {
        if (enabled) jsonChars += n;
    }

    /**
     * 记录一次缓存未命中时的 NBT 体积。只在未命中路径调用——这里是唯一会分配的地方。
     */
    public static void recordNbt(String id, NBTTagCompound n) {
        if (!enabled) return;
        int raw;
        int gz;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            CompressedStreamTools.write(n, new DataOutputStream(bos));
            raw = bos.size();
            gz = CompressedStreamTools.compress(n).length;
        } catch (IOException e) {
            return;
        }
        uncompressedBytes += raw;
        compressedBytes += gz;

        long[] slot = perId.get(id);
        if (slot == null) {
            if (perId.size() >= MAX_IDS) {
                idsTruncated = true;
                return;
            }
            slot = new long[3];
            perId.put(id, slot);
        }
        slot[0]++;
        slot[1] += raw;
        if (raw > slot[2]) slot[2] = raw;
    }

    // ---------------------------------------------------------------- 输出

    /** 配置重载时调用：不同规则集的数据不应混在一起。 */
    public static void onConfigReload() {
        if (enabled) reset();
    }

    public static void reset() {
        getBodyCalls = 0;
        cacheHits = 0;
        cacheMisses = 0;
        evalCalls = 0;
        invokeCalls = 0;
        evalNanos = 0;
        invokeNanos = 0;
        jsonChars = 0;
        uncompressedBytes = 0;
        compressedBytes = 0;
        perId.clear();
        idsTruncated = false;
        lastReportMillis = System.currentTimeMillis();
        reportCountdown = REPORT_EVERY_CALLS;
    }

    private static void maybeReport() {
        if (reportIntervalSeconds <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastReportMillis < reportIntervalSeconds * 1000L) return;
        lastReportMillis = now;
        LogHelper.info(report());
    }

    /** 纯文本汇总，供日志使用。 */
    public static String report() {
        StringBuilder sb = new StringBuilder("=== OmniOcular stats ===");
        sb.append("\ngetBody=")
            .append(getBodyCalls)
            .append("  hit=")
            .append(cacheHits)
            .append(" (")
            .append(pct(cacheHits, getBodyCalls))
            .append("%)  miss=")
            .append(cacheMisses);
        sb.append("\neval   calls=")
            .append(evalCalls)
            .append("  total=")
            .append(ms(evalNanos))
            .append("ms  avg=")
            .append(us(evalNanos, evalCalls))
            .append("us");
        sb.append("\ninvoke calls=")
            .append(invokeCalls)
            .append("  total=")
            .append(ms(invokeNanos))
            .append("ms  avg=")
            .append(us(invokeNanos, invokeCalls))
            .append("us");
        sb.append("\nNBT jsonChars=")
            .append(jsonChars)
            .append("  rawBytes=")
            .append(uncompressedBytes)
            .append("  gzipBytes=")
            .append(compressedBytes);
        sb.append("\n--- top ")
            .append(TOP_N)
            .append(" by max NBT size ---");
        for (String line : topLines()) {
            sb.append('\n')
                .append(line);
        }
        if (idsTruncated) {
            sb.append("\n(per-id table truncated at ")
                .append(MAX_IDS)
                .append(")");
        }
        return sb.toString();
    }

    /** 供 /oo stats 在聊天栏逐行输出，带上颜色。 */
    public static List<String> reportChatLines() {
        List<String> out = new ArrayList<>();
        out.add("§6=== OmniOcular stats ===");
        out.add(
            "§7getBody §f" + getBodyCalls
                + "§7  hit §a"
                + cacheHits
                + " §7("
                + pct(cacheHits, getBodyCalls)
                + "%)§7  miss §c"
                + cacheMisses);
        out.add(
            "§7eval   §f" + evalCalls + "§7 次, 共 §f" + ms(evalNanos) + "ms§7, 均 §f" + us(evalNanos, evalCalls) + "us");
        out.add(
            "§7invoke §f" + invokeCalls
                + "§7 次, 共 §f"
                + ms(invokeNanos)
                + "ms§7, 均 §f"
                + us(invokeNanos, invokeCalls)
                + "us");
        out.add(
            "§7json §f" + jsonChars
                + "§7 字符, NBT §f"
                + uncompressedBytes
                + "§7 B (gzip §f"
                + compressedBytes
                + "§7 B)");
        out.add("§6--- top " + TOP_N + " 最大 NBT ---");
        for (String line : topLines()) {
            out.add("§7" + line);
        }
        if (idsTruncated) {
            out.add("§8(per-id 表已在 " + MAX_IDS + " 条截断)");
        }
        return out;
    }

    private static List<String> topLines() {
        List<Map.Entry<String, long[]>> list = new ArrayList<>(perId.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[2], a.getValue()[2]));
        List<String> out = new ArrayList<>();
        int n = Math.min(TOP_N, list.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, long[]> e = list.get(i);
            long[] v = e.getValue();
            out.add(
                String
                    .format("%-42s miss=%-5d max=%7d B  avg=%7d B", e.getKey(), v[0], v[2], v[1] / Math.max(1L, v[0])));
        }
        return out;
    }

    private static long pct(long part, long total) {
        return total > 0 ? part * 100 / total : 0;
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }

    private static String us(long nanos, long calls) {
        return calls > 0 ? String.format("%.1f", nanos / 1000.0 / calls) : "0";
    }
}
