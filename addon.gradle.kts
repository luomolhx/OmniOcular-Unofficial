// ============================================================================
// addon.gradle.kts —— GTNHGradle 的 AddonScriptModule 在配置期自动 apply。
//
// 目的：同一份 src/main 源码，一次构建额外产出一个「专用服务端精简版」：
//   build/libs/<archivesName>-<version>-server-dev.jar   未 reobf，调试用
//   build/libs/<archivesName>-<version>-server.jar       reobf，给专用服务端
// CI 的 `gh release create ... ./build/libs/*.jar` 是通配，无需改 CI。
//
// 三条硬约束：
//  1) 不新建 source set、不复制任何 .java —— class 全部来自 main 的 output，
//     因此自动跟随 master（含 EntityHandler.getNBTData 里 EntityList.getEntityString
//     写回 id 的修正）。推论：GRADLETOKEN_* 的替换只需发生在 main 的 compileJava 上，
//     本方案不需要它在别处生效。
//  2) 与主 jar 的相对路径集合完全不相交。Gradle 9 对重复条目默认抛
//     InvalidUserCodeException，这里再显式 FAIL，把"意外重叠"变成响亮的构建失败。
//  3) manifest 属性不继承（GTNHGradle 只配默认 jar 任务），手工补。
// ============================================================================

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.tasks.Jar

val mainSourceSet = extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName("main")

// 服务端精简版不要的 class。每一条都有理由：
val serverExcludes = listOf(
    // 直接引用客户端独有 API（net.minecraft.client / codechicken / net.minecraftforge.client）
    "me/exz/omniocular/client/**",                   // CommandLookFor / GuiFactory / OOConfigGui
    "me/exz/omniocular/proxy/ClientProxy*.class",    // Minecraft + NEI GuiContainerManager + ClientCommandHandler
    "me/exz/omniocular/waila/TooltipHandler*.class", // Minecraft + GuiContainer + NEI
    // 只被客户端路径 / 客户端 mixin 引用
    "me/exz/omniocular/mixins/**",                   // MixinHUDHandlerFMP
    "me/exz/omniocular/LateMixinPlugin*.class",      // 精简版不带 mixin 引导，留着白送 NoClassDefFoundError
    "me/exz/omniocular/waila/FMPHandler*.class",     // 只被 MixinHUDHandlerFMP 引用
    "me/exz/omniocular/scripts/GTNHScript*.class",   // 只由 Config.scriptClassName 经 PluginEngine.init()
                                                     // （仅 ClientProxy.postInit 调用）按名加载
)

val serverJar = tasks.register<Jar>("serverJar") {
    group = "build"
    description = "专用服务端精简版 jar（未 reobf，调试用）"

    // reobfServerJar 不继承 classifier，这里必须与主 jar（jar=dev / reobfJar=无）都不同名
    archiveClassifier.set("server-dev")

    // 1) main 的全部 class，减去上面的排除项
    from(mainSourceSet.output) {
        include("me/exz/omniocular/**")
        serverExcludes.forEach { exclude(it) }
    }
    // 2) 访问转换器：FML 的 ModAccessTransformer 从 <jar>!META-INF/<FMLAT> 读它。
    //    NBTHelper / NBTSerializer 用到 tagList / tagMap，正是它 public 化的，必须留。
    from(mainSourceSet.output) { include("META-INF/OmniOcular_at.cfg") }
    // 3) mcmod.info 复用 main/processResources 展开后的产物：${} 展开只挂在 main 的
    //    processResources 上，自己写一份 src/.../mcmod.info 只会留下字面量 "${modId}"。
    //    modid 与 version 必须与主 jar 逐字相同（FML 握手未声明 acceptableRemoteVersions，
    //    要求远端版本串完全相等）。
    from(tasks.named("processResources")) { include("mcmod.info") }

    // manifest 只补精简版需要的：
    //  - 去掉 TweakClass / MixinConfigs / ForceLoadAsMod ⇒ 服务端不必装 UniMixins
    //  - 去掉 FMLCorePluginContainsFMLMod ⇒ 本模组没有 coremod
    //  - 保留 FMLAT ⇒ 同上第 2 点
    manifest { attributes["FMLAT"] = "OmniOcular_at.cfg" }

    // 路径集合不相交是设计约束，不是巧合：出现重复就失败
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

// reobfServerJar 由 RFG 的 task rule 自动创建（任务名去掉 reobf 前缀即 serverJar）。
// 必须在 serverJar 注册之后再 named()，否则 rule 找不到 subject 会直接 return。
tasks.named<Jar>("reobfServerJar") {
    // 关键一行：ReobfuscatedJar#setInputJarFromTask 刻意不复制 classifier，
    // 不设它就会输出 <name>-<version>.jar，与 reobfJar 撞同一个文件
    archiveClassifier.set("server")
}

// reobfJar 已由 RFG 挂到 assemble 上，这里补上精简版
tasks.named("assemble") { dependsOn(tasks.named("reobfServerJar")) }
