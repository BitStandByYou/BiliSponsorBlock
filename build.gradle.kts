// 顶层构建文件。
//
// 说明：AGP 9.x 自带 Kotlin 支持（运行时依赖 kotlin-gradle-plugin 2.2.10），
// 因此这里不再单独声明 / 应用 org.jetbrains.kotlin.android 插件，否则会与
// AGP 内置的 `kotlin` 扩展冲突（Cannot add extension with name 'kotlin'）。
plugins {
    id("com.android.application") version "9.4.1" apply false
}
