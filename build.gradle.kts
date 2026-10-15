plugins {
    java
}

// La version du plugin vient du fichier VERSION à la racine.
// La version de Minecraft ciblée se choisit avec -PmcVersion=26.2 (défaut : 26.1).
val pluginVersion = file("VERSION").readText().trim()
val mcVersion = providers.gradleProperty("mcVersion").getOrElse("26.1")
val paperApi = providers.gradleProperty("paperApi").getOrElse("26.1.2.build.74-stable")

group = "fr.basile"
version = pluginVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    val props = mapOf("version" to pluginVersion, "mcVersion" to mcVersion)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName.set("isohypse-$pluginVersion-minecraft-$mcVersion.jar")
}
