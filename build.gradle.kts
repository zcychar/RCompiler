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

// 1. 定义测试用例的根目录
val testCasesDir = file("src/main/resources/RCompiler-Testcases/semantic-2")

// 2. 检查目录是否存在
if (testCasesDir.isDirectory) {
  // 3. 动态创建任务
  testCasesDir.listFiles { file -> file.isDirectory }?.forEach { testDir ->

    val testCaseName = testDir.name.toCamelCase()
    val taskName = testCaseName

    tasks.register(taskName) {
      group = "Compiler Tests"
      description = "Runs the compiler test case '${testDir.name}'."
      inputs.dir(testDir)
      dependsOn(sourceSets.main.get().processResourcesTaskName)
      // 将所有逻辑放入 doLast 块，它会在任务执行时运行
      doLast {
        val infoFile = testDir.resolve("testcase_info.json")
        val sourceFile = testDir.resolve("${testDir.name}.rx")

        if (!infoFile.exists() || !sourceFile.exists()) {
          throw GradleException("Test '${testDir.name}' is malformed: missing required files.")
        }

        logger.lifecycle("Executing compiler for test: ${testDir.name}...")

        val execResult = project.javaexec {
          classpath = sourceSets.main.get().runtimeClasspath
          mainClass.set("MainKt")
          val resourcesDir = sourceSets.main.get().resources.srcDirs.first()
          val relativePath = sourceFile.relativeTo(resourcesDir).path
          args = listOf(relativePath)
          isIgnoreExitValue = true
        }

        // 从返回的 execResult 对象中安全地获取退出码
        val actualExitCode = execResult.exitValue

        // --- 验证逻辑保持不变 ---
        val jsonText = infoFile.readText()
        val regex = """"compileexitcode"\s*:\s*(-?\d+)""".toRegex()
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