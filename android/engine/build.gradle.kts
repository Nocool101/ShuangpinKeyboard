plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // 端侧重排模型：编译期只要 ONNX Runtime 的 API，
    // 运行时由 app 模块提供（onnxruntime-android）。
    // 引擎模块不引 Android SDK，ORT 是纯 JNI 库，不破这条线。
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

/**
 * 导出「本引擎的候选池」，供端侧模型评估使用。
 *
 * 端侧模型是在别人的候选池上训练和测量的，想知道它对**我们**有没有用，
 * 必须把候选池换成我们自己的——而且要和 App 里显示的一致。
 *
 * 用法：
 * ```
 * gradlew :engine:exportEvalPool -PevalPool=<评测集.jsonl> -PevalOut=<输出.jsonl>
 * ```
 */
tasks.register<JavaExec>("exportEvalPool") {
    group = "verification"
    description = "导出本引擎的候选池，供端侧模型评估"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.shuangpin.engine.eval.ExportEvalPool")
    args(
        (project.findProperty("evalPool") as String?)
            ?: "D:/tmp/ime-analysis/chinese-ime-lm-main/eval/sentences-ctx-v1/pool.jsonl",
        (project.findProperty("evalOut") as String?) ?: "D:/tmp/ime-analysis/our-pool.jsonl",
    )
}

