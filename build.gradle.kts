plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

// DriftMQ 는 런타임 의존성이 0 이다 (순수 JDK). 테스트도 커스텀 러너(MiniTest)를 쓴다.
repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.add("-Xlint:all")
}

tasks.jar {
    // 의존성이 없으므로 이 JAR 자체가 실행 가능한 "fat JAR" 이다: java -jar driftmq.jar
    manifest {
        attributes(
            "Main-Class" to "io.driftmq.cli.Main",
            "Implementation-Title" to "DriftMQ",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        encoding = "UTF-8"
    }
}

// ── 테스트: 커스텀 MiniTest 러너 (JUnit 미사용) ──────────────────────────────
val miniTestClasses = listOf(
    "io.driftmq.protocol.CodecTest",
    "io.driftmq.broker.MessageStoreTest",
    "io.driftmq.broker.RecoveryTest",
    "io.driftmq.broker.OffsetManagerTest",
    "io.driftmq.broker.AckManagerTest",
    "io.driftmq.client.ClientIntegrationTest",
    "io.driftmq.cli.CliTest",
    "io.driftmq.scenario.MvpScenarioTest",
    "io.driftmq.scenario.CorrectnessTest",
)

val miniTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the DriftMQ MiniTest suite (unit + MVP scenario)."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.driftmq.test.MiniTest"
    args = miniTestClasses
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// 기본 test 태스크는 JUnit 을 기대하므로 비활성화하고 miniTest 로 대체한다.
tasks.test { enabled = false }
tasks.check { dependsOn(miniTest) }
