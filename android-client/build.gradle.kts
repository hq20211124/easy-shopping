// 根构建脚本：插件版本统一在此声明（单 app module；P1 拆多模块见 README「工程结构」）
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}
