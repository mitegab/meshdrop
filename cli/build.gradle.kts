plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(17) }
application { mainClass.set("com.meshdrop.cli.MainKt"); applicationName = "meshdrop" }
// Compile the actual Android wire/storage/crypto implementation, not a second protocol copy.
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/kotlin")
        kotlin.include("com/meshdrop/cli/**", "com/meshdrop/core/model/**", "com/meshdrop/core/integrity/**",
            "com/meshdrop/core/resume/**", "com/meshdrop/core/persistence/**", "com/meshdrop/core/security/**",
            "com/meshdrop/core/pairing/**", "com/meshdrop/core/util/Log.kt", "com/meshdrop/data/IncomingFileStore.kt",
            "com/meshdrop/transfer/crypto/**", "com/meshdrop/transfer/protocol/**", "com/meshdrop/transfer/server/**",
            "com/meshdrop/transfer/mesh/**", "com/meshdrop/transfer/link/DuplexConnection.kt",
            "com/meshdrop/transfer/link/FramedLink.kt", "com/meshdrop/transfer/client/PayloadSource.kt",
            "com/meshdrop/transfer/client/TransferClient.kt")
        kotlin.exclude("**/AndroidDeviceIdentity.kt", "**/VerifiedPeers.kt")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation(libs.json.test)
    testImplementation(libs.junit)
}
