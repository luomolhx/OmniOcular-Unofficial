package me.exz.omniocular.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTP 下载工具。
 *
 * <p>
 * 原先这段逻辑内嵌在 {@code ScriptEngineHandler} 里，配置更新器需要同一套超时/状态码处理，
 * 故提取出来共用（超时值、非 200 抛错的行为保持一致）。
 */
public class HttpUtil {

    /**
     * 伪装成普通浏览器，挡住按 UA 拦人的站点。
     *
     * <p>
     * 原来那串 IE 5.0 的 UA 会被 Gitee 的 WAF 直接 403（raw 与 API 都拦），配置更新指向
     * Gitee 镜像时一个文件都下不下来；现代浏览器 UA 在 Gitee、jsdelivr、Maven Central
     * 上都实测可用。
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        + " (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /**
     * GET 一个 URL 并返回全部内容。
     *
     * <p>
     * 非 200 一律抛 {@link IOException}——直接取 {@code getInputStream()} 的话，404/403 会把
     * 错误页面当成正常内容写盘。连接与读取超时都必须设：只设连接超时时，对端接受连接后
     * 不再响应会永久挂起（本方法调用在后台线程，挂起会占住整个更新流程）。
     */
    public static byte[] downloadBytes(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(5 * 1000);
        conn.setReadTimeout(30 * 1000);
        conn.setRequestProperty("User-Agent", USER_AGENT);

        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " from " + urlStr);
            }
            try (InputStream in = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
                return bos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
