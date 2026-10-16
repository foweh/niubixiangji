package com.hinnka.mycamera.integration

import java.io.File

/**
 * 集成测试只读素材访问器。
 *
 * 设计约束（与 delivery/PATTERN_SPEC.md 的集成自查约定一致）：
 *  - 纯 JVM：只依赖 java.io / java.nio，**不 import 任何 android.\* 或工程内 Android 类**；
 *  - 工程源码以「文本断言」方式被检查，因此这些用例在无 Android SDK 的 JVM 上也能跑；
 *  - 路径解析对 Gradle 的工作目录不敏感（app/ 或工程根都能命中）。
 */
object IntegrationSources {

    /** 工程根（依次向上探测含 app/src/main 的目录）。 */
    val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var cur: File? = start
        val candidates = ArrayList<File>()
        while (cur != null) {
            candidates += File(cur, "app/src/main")
            candidates += File(cur, "src/main")
            cur = cur.parentFile
        }
        candidates.firstOrNull { it.isDirectory }?.parentFile ?: start
    }

    /** 读 `app/src/main/...` 相对路径的文本；不存在返回空串（调用方自行断言）。 */
    fun readMain(relative: String): String = readUnder("src/main", relative)

    /** 读 `app/src/test/...` 相对路径的文本。 */
    fun readTest(relative: String): String = readUnder("src/test", relative)

    private fun readUnder(prefix: String, relative: String): String {
        val f = File(root, "app/$prefix/$relative")
        return if (f.isFile) f.readText() else ""
    }

    /** 工程根下的任意文件（补丁 md、配置等）。 */
    fun readProject(relative: String): String {
        val f = File(root, relative)
        return if (f.isFile) f.readText() else ""
    }

    fun existsMain(relative: String): Boolean = File(root, "app/src/main/$relative").isFile

    /** 递归列出 `app/src/main/java` 下的全部 .kt 文件（相对 app/src/main 的路径）。 */
    fun kotlinSources(): List<String> {
        val base = File(root, "app/src/main/java")
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(File(root, "app/src/main")).path }
            .sorted()
            .toList()
    }

    /** 统计某个字面量在文件中出现的次数。 */
    fun count(haystack: String, needle: String): Int {
        if (needle.isEmpty() || haystack.isEmpty()) return 0
        var i = 0
        var n = 0
        while (true) {
            val k = haystack.indexOf(needle, i)
            if (k < 0) return n
            n++
            i = k + needle.length
        }
    }

    /** 某个字面量在全树（app/src/main/java + cpp + assets + res）出现的文件数。 */
    fun filesContaining(needle: String): List<String> {
        val base = File(root, "app/src/main")
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "json", "cpp", "h", "kts") }
            .filter { it.readText().contains(needle) }
            .map { it.relativeTo(base).path }
            .sorted()
            .toList()
    }

    /** 把 camelCase 标识符转成 UPPER_SNAKE（用于「用户可调值默认 == 既有常量」的核对）。 */
    fun upperSnake(name: String): String {
        val sb = StringBuilder()
        name.forEachIndexed { i, c ->
            if (c.isUpperCase() && i > 0 && !name[i - 1].isUpperCase()) sb.append('_')
            sb.append(c.uppercaseChar())
        }
        return sb.toString()
    }
}
