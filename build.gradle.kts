plugins { java }

group = "io.github.freecoreguild"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("../FreeCoreEssentials/libs/VaultAPI-1.7.1.jar"))
    compileOnly("com.zaxxer:HikariCP:6.3.2")
    compileOnly(files("../../MCSManager/daemon/data/InstanceData/FreeCore-Survival/plugins/【变量】PlaceholderAPI-2.12.3.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

// Bukkit is compileOnly in production, but the offline audit loads its YAML parser.
sourceSets.test {
    compileClasspath += configurations.compileClasspath.get()
    runtimeClasspath += configurations.compileClasspath.get()
}

// Run the language guard on every check/build, without a network test framework.
val languageAudit = tasks.register<JavaExec>("languageAudit") {
    group = "verification"
    description = "Reject hardcoded player language and missing external language keys"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set("io.github.freecoreguild.LanguageAudit")
    args(project.projectDir.absolutePath)
}
tasks.check { dependsOn(languageAudit) }
// Verification entry point is the JavaExec guard above, not a JUnit test class.
tasks.test { failOnNoDiscoveredTests.set(false) }

tasks.jar {
    archiveBaseName.set("FreeCoreGuild")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
