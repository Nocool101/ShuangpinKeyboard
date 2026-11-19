pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 刻意不启用 dependencyResolutionManagement/PREFER_SETTINGS：
// 很多国内环境（包括本机）通过 ~/.gradle/init.d/*.gradle 往 project 级
// 注入阿里云镜像，PREFER_SETTINGS 会把这些镜像判为冲突并跳过。
// 仓库地址统一写在各模块的 build.gradle.kts 里，init 脚本的镜像会排在前面。

rootProject.name = "ShuangpinIME"

// :engine —— 纯 JVM 模块，承载双拼引擎与全部数据。
// 刻意不依赖 Android SDK，这样引擎逻辑可以用普通 JUnit 在秒级内跑完，
// 也方便以后复用到桌面端或服务端。
include(":engine")

// :app —— 安卓输入法外壳。
include(":app")
