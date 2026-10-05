plugins {
    java
    application
}

group = "io.github.mpmp666"
version = "1.0.0"

java {
    // No toolchain block: CI has to use the same JDK that builds Nukkit-MOT (21), while the
    // proxy itself targets 17 bytecode.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

/**
 * The modern Bedrock packet codecs (cn.nukkit.network.protocol.*) come from the Nukkit-MOT
 * server jar. It is not published to Maven Central, so either drop it into `libs/` or point
 * `-PnukkitJar=/path/to/Nukkit-MOT-SNAPSHOT.jar` at an existing copy.
 *
 * `implementation` (not `shadowJar`) on purpose: the proxy only needs those classes at compile
 * time and from the server jar on its runtime classpath — it never bundles them.
 */
val nukkitJar: String? = (findProperty("nukkitJar") as String?)

dependencies {
    implementation(fileTree("libs") { include("*.jar") })
    if (nukkitJar != null) {
        implementation(files(nukkitJar))
    }
}

application {
    mainClass.set("proxy.ProxyMain")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "proxy.ProxyMain")
    }
}
