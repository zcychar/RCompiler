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


// === Testcases roots ===
val testCaseRoots = listOf(
    file("src/main/resources/RCompiler-Testcases"),          // 远端克隆仓库（保持只读）
    file("src/main/resources/RCompiler-Local-Testcases"),    // 本地自定义用例根     // 现有本地用例（兼容路径）
)

val allStageTasks = mutableListOf<TaskProvider<*>>()
val localStageTasks = mutableListOf<TaskProvider<*>>()

// 扫描每个根目录下的阶段（stage）文件夹
testCaseRoots.filter { it.isDirectory }.forEach { rootDir ->

    rootDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.forEach { stageDir ->

        val stageNameCamel = stageDir.name.toCamelCase().replaceFirstChar { it.uppercase() }
        val rootPrefix = "local"
        val isRemoteRoot = rootDir.name == "RCompiler-Testcases"
        val stageTaskName = if (isRemoteRoot) stageNameCamel else "${rootPrefix}${stageNameCamel}"
        val stageTestTasks = mutableListOf<TaskProvider<*>>()

        logger.lifecycle("Discovered stage: ${stageDir.name} in root '${rootDir.name}' -> Creating tasks with prefix '${stageTaskName}'")

        // 遍历第二层目录，即阶段下的每个“测试点”（如 basic1）
        stageDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.forEach { testCaseDir ->

            val caseNameCamel = testCaseDir.name.toCamelCase().replaceFirstChar { it.uppercase() }
            val individualTaskName = "${stageTaskName}${caseNameCamel}"

            val individualTaskProvider = tasks.register(individualTaskName) {
                group = "Compiler Individual Tests"
                description = "Runs compiler test '${testCaseDir.name}' from stage '${stageDir.name}' (root='${rootDir.name}')."

                inputs.dir(testCaseDir)
                dependsOn(sourceSets.main.get().processResourcesTaskName)

                doLast {
                    val sourceFile = testCaseDir.resolve("${testCaseDir.name}.rx")
                    val resourcesDir = sourceSets.main.get().resources.srcDirs.first()
                    val relativePath = sourceFile.relativeTo(resourcesDir).path

                    val infoFile = testCaseDir.resolve("testcase_info.json")
                    if (!infoFile.exists()) throw GradleException("testcase_info.json not found for test '${testCaseDir.name}'")

                    // 读取 Gradle 属性以控制是否开启 debug 模式（默认关闭）
                    val debugProp = (project.findProperty("debug") as String?)?.lowercase()
                    val enableDebug = debugProp == "true" || debugProp == "1" || debugProp == "yes" || debugProp == "y"

                    val argsList = mutableListOf(relativePath)
                    if (enableDebug) argsList.add("--debug")

                    val execResult = project.javaexec {
                        classpath = sourceSets.main.get().runtimeClasspath
                        mainClass.set("MainKt")
                        args = argsList
                        isIgnoreExitValue = true
                    }

                    val jsonText = infoFile.readText()
                    val exitCodeString =
                        """"compileexitcode"\s*:\s*(-?\d+)""".toRegex().find(jsonText)?.groups?.get(1)?.value
                            ?: throw GradleException("Could not find 'compileexitcode' key in ${infoFile.path}")

                    val expectedExitCode = exitCodeString.toInt()
                    val actualExitCode = execResult.exitValue

                    logger.lifecycle("\n--- Test '${testCaseDir.name}' from stage '${stageDir.name}' (root='${rootDir.name}') ---")
                    logger.lifecycle("   > Expected exit code: $expectedExitCode")
                    logger.lifecycle("   > Actual exit code:   $actualExitCode")

                    val passed =
                        (expectedExitCode == 0 && actualExitCode == 0) || (expectedExitCode != 0 && actualExitCode != 0)
                    if (!passed) {
                        logger.error("   ❌ FAILED")
                        throw GradleException("Test case '${testCaseDir.name}' failed.")
                    } else {
                        logger.lifecycle("   ✅ PASSED")
                    }
                }
            }
            stageTestTasks.add(individualTaskProvider)
        }

        val stageTaskProvider = tasks.register(stageTaskName) {
            group = "Compiler Stage Tests"
            description = "Runs all tests for stage '${stageDir.name}' (root='${rootDir.name}')."
            dependsOn(stageTestTasks)
        }
        allStageTasks.add(stageTaskProvider)
        if (!isRemoteRoot) localStageTasks.add(stageTaskProvider)
    }
}

fun String.toCamelCase(): String {
    return this.split('-', '_').mapIndexed { index, s ->
        if (index == 0) s else s.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}

tasks.register("localTests") {
    group = "Verification"
    description = "Runs all local test stages (non-remote roots)."
    dependsOn(localStageTasks)
}

tasks.register("allCompilerTests") {
    group = "Verification"
    description = "Runs all compiler tests across all roots and stages."
    dependsOn(allStageTasks)
}