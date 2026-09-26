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
 * 故提取出来共用（只搬不改：超时值、UA、非 200 抛错的行为都保持一致）。
 */
public class HttpUtil {

    /** 防止屏蔽程序抓取而返回 403 错误。 */
    private static final String USER_AGENT = "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT; DigExt)";

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
