plugins {
    kotlin("jvm") version "2.2.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(24)
}
// --- 在 build.gradle.kts 文件末尾添加以下代码 ---

// 1. 定义测试用例的根目录
val testCasesDir = file("src/test/resources/RCompiler-Testcases/semantic-1")

// 2. 检查目录是否存在
if (testCasesDir.isDirectory) {
    // 3. 动态创建任务
    testCasesDir.listFiles { file -> file.isDirectory }?.forEach { testDir ->

        val testCaseName = testDir.name.toCamelCase()
        val taskName = "test${testCaseName.capitalize()}"

        // --- 修复点 1: 任务类型不再是 JavaExec ---
        // 我们注册一个通用任务，其动作是执行 javaexec 方法。
        tasks.register(taskName) {
            group = "Compiler Tests"
            description = "Runs the compiler test case '${testDir.name}'."

            // 将所有逻辑放入 doLast 块，它会在任务执行时运行
            doLast {
                val infoFile = testDir.resolve("testcase_info.json")
                val sourceFile = testDir.resolve("${testDir.name}.rx")

                if (!infoFile.exists() || !sourceFile.exists()) {
                    throw GradleException("Test '${testDir.name}' is malformed: missing required files.")
                }

                logger.lifecycle("Executing compiler for test: ${testDir.name}...")

                // --- 修复点 2: 使用 project.javaexec 方法并捕获其返回值 ---
                // 这个方法会立即执行并返回一个我们可以安全使用的 ExecResult 对象。
                val execResult = project.javaexec {
                    // 在这里配置执行细节
                    classpath = sourceSets.main.get().runtimeClasspath
                    mainClass.set("MainKt") // 确保主类名正确
                    args = listOf(sourceFile.absolutePath)
                    isIgnoreExitValue = true // 关键：我们自己处理退出码，所以让 Gradle 不要因为非零退出码而失败
                }

                // 从返回的 execResult 对象中安全地获取退出码
                val actualExitCode = execResult.exitValue

                // --- 验证逻辑保持不变 ---
                val jsonText = infoFile.readText()
                val regex = """"compileexitcode"\s*:\s*(\d+)""".toRegex()
                val match = regex.find(jsonText)
                val exitCodeString = match?.groups?.get(1)?.value
                    ?: throw GradleException("Could not find 'compileexitcode' key in ${infoFile.path}")

                val expectedExitCode = exitCodeString.toInt()

                logger.lifecycle("\n--- Test '${testDir.name}' ---")
                logger.lifecycle("   > Expected exit code: $expectedExitCode")
                logger.lifecycle("   > Actual exit code:   $actualExitCode")

                val passed = (expectedExitCode == 0 && actualExitCode == 0) ||
                    (expectedExitCode != 0 && actualExitCode != 0)

                if (passed) {
                    logger.lifecycle("   ✅ PASSED")
                } else {
                    logger.error("   ❌ FAILED")
                    throw GradleException("Test case '${testDir.name}' failed. Expected exit code: $expectedExitCode, but got: $actualExitCode")
                }
            }
        }
    }
}

// 辅助函数保持不变
fun String.toCamelCase(): String {
    return this.split('-', '_').mapIndexed { index, s ->
        if (index == 0) s else s.capitalize()
    }.joinToString("")
}

// “运行所有测试”的任务也需要小幅修改
tasks.register("allCompilerTests") {
    group = "Compiler Tests"
    description = "Runs all compiler verification tests."
    // 依赖关系现在是基于任务名和组名，而不是类型
    dependsOn(tasks.matching { it.group == "Compiler Tests" && it.name != "allCompilerTests" })
}