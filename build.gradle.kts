
plugins {
    // 顺序重要：必须排在 gtnhconvention 之前。
    // GTNH 约定插件在它自己的 apply() 期间就会求值 dependencies.gradle，
    // 那时 elytraModpackVersion 扩展必须已经存在。
    // 不能写 version：settings.gradle.kts 的 settings 插件已把 cn.elytra:elytra-conventions:1.2.1
    // 放到 settings 的 classpath 并继承给本项目，重复指定版本会触发
    // "already on the classpath with an unknown version"。
    id("cn.elytra.gradle.conventions")
    id("com.gtnewhorizons.gtnhconvention")
}

// 注意：不要在这里写 elytraModpackVersion { gtnhVersion = ... }。
// 该块在 plugins {} 之后才执行，而 dependencies.gradle 早已被求值，
// 且 gtnhVersion 带 finalizeValueOnRead()，会成为读不到的迟到赋值。
// 版本统一由 gradle.properties 的 elytra.manifest.version 提供。
