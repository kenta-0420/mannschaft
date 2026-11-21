plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// CMP-261111-0508: ShardAssignment / ShardBalanceGuard の単体テスト基盤。
// CI では backend-ci.yml の detect ジョブ（旧 shard-coverage-guard を同居した step）で実行する。
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
