package me.exz.omniocular.handler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import cpw.mods.fml.common.FMLCommonHandler;
import me.exz.omniocular.util.HttpUtil;
import me.exz.omniocular.util.LogHelper;

public class ScriptEngineHandler {

    public static ScriptEngineManager manager;

    /** 只保留 HTTPS 源：明文 HTTP 可被中间人替换为任意 jar，而这个 jar 会被注入系统类加载器。 */
    private static final String[] repos = new String[] { "https://repo1.maven.org/maven2/",
        "https://repo.maven.apache.org/maven2/", "https://mirrors.cloud.tencent.com/repository/maven/",
        "https://maven.aliyun.com/repository/public/" };

    private static final String NASHORN_PATH = "org/openjdk/nashorn/nashorn-core/15.4/nashorn-core-15.4.jar";

    /**
     * nashorn-core-15.4.jar 的 SHA-256，取自 Maven Central 随构件发布的官方校验值
     * （https://repo1.maven.org/maven2/org/openjdk/nashorn/nashorn-core/15.4/nashorn-core-15.4.jar.sha256）。
     *
     * <p>
     * 之所以必须校验：这个 jar 会被反射注入 <b>AppClassLoader</b>（所有加载器里优先级最高的一层），
     * 投毒影响的是整个游戏进程，而不是被限制在模组的类加载器内。校验和一致性也覆盖了各大镜像，
     * 因为它们分发的是同一个构件。
     */
    private static final String NASHORN_SHA256 = "6f816e84dfd63a81d4eaa7829c08337bbaff3ec683ff3bf6bbd90d017a00dc6f";

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 必须实现的算法，走到这里说明 JVM 有问题
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 下载 + 校验 + 落盘。任一步失败都抛 IOException，不会留下半成品。 */
    private static void downloadAndVerify(String urlStr, File saveFile) throws IOException {
        byte[] data = HttpUtil.downloadBytes(urlStr);
        String actual = sha256Hex(data);
        if (!NASHORN_SHA256.equalsIgnoreCase(actual)) {
            throw new IOException(
                "SHA-256 mismatch for " + urlStr + ": expected " + NASHORN_SHA256 + " but got " + actual);
        }
        File dir = saveFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory " + dir);
        }
        try (FileOutputStream fos = new FileOutputStream(saveFile)) {
            fos.write(data);
        }
    }

    public static void initScriptEngineManager() {
        if (Double.parseDouble(System.getProperty("java.class.version")) >= 55.0) {
            ClassLoader classLoader = ClassLoader.getSystemClassLoader();
            boolean succeed;

            try {
                classLoader.loadClass("org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory");
                succeed = true;
            } catch (ClassNotFoundException e) {
                succeed = false;
            }

            if (!succeed) {
                File jarFile = new File(Launch.minecraftHome, "/mods/oo/nashorn-core-15.4.jar");
                LogHelper.info("Nashorn core path: " + jarFile);
                if (!jarFile.exists() || !jarFile.isFile()) {
                    LogHelper.info("Nashorn core not exist!");
                    LogHelper.info("Downloading...!");

                    boolean downloadSucceed = false;
                    for (String urlBase : repos) {
                        String url = urlBase + NASHORN_PATH;
                        try {
                            LogHelper.info("Download and verify from: " + url);
                            downloadAndVerify(url, jarFile);
                            LogHelper.info("Download succeed (SHA-256 verified).");
                            downloadSucceed = true;
                            break;
                        } catch (IOException e) {
                            LogHelper.fatal("Download/verify failed: " + e.getMessage());
                        }
                    }
                    if (!downloadSucceed) {
                        LogHelper.fatal("Unable to download and verify " + jarFile.getPath());
                        LogHelper.fatal(
                            "Download nashorn-core-15.4.jar manually and place it at " + jarFile.getPath()
                                + " (SHA-256 must be "
                                + NASHORN_SHA256
                                + ")");
                        FMLCommonHandler.instance()
                            .exitJava(-1, false);
                    }

                }

                if (classLoader.getClass()
                    .getName()
                    .equals("jdk.internal.loader.ClassLoaders$AppClassLoader")) {

                    for (Method method : classLoader.getClass()
                        .getDeclaredMethods()) {
                        if (method.getName()
                            .equals("appendToClassPathForInstrumentation")) {
                            method.setAccessible(true);
                            try {
                                method.invoke(classLoader, jarFile.toString());
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                throw new RuntimeException(e);
                            }
                            break;
                        }
                    }
                }
            }
            manager = new ScriptEngineManager(classLoader);
        } else {
            try {
                LaunchClassLoader launchClassLoader = Launch.classLoader;
                launchClassLoader.addClassLoaderExclusion("jdk.nashorn");

                Class<?> clazz = launchClassLoader.loadClass("jdk.nashorn.api.scripting.NashornScriptEngineFactory");

                manager = new ScriptEngineManager(launchClassLoader);
                manager.registerEngineName("js", (ScriptEngineFactory) clazz.newInstance());
            } catch (ClassNotFoundException e) {
                LogHelper.info("The Java has not Nashorn Javascript Engine!");
            } catch (InstantiationException | IllegalAccessException e) {
                LogHelper.info("Nashorn Javascript Engine create failed!");
            }
        }

        if (manager == null) manager = new ScriptEngineManager();

        List<ScriptEngineFactory> factories = manager.getEngineFactories();
        for (ScriptEngineFactory f : factories) {
            LogHelper.info("Available Engine: " + f.getLanguageName() + " " + f.getEngineName() + " " + f.getNames());
        }
        LogHelper.info("Java Home: " + System.getProperty("java.home"));
    }
}
