plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.mannschaft"
version = "0.0.1-SNAPSHOT"
description = "Universal Organization Management Platform"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val mapstructVersion = "1.6.3"

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.retry:spring-retry")

    // Flyway (MySQL)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // MapStruct
    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")

    // Lombok（MapStruct より先に処理させるため annotationProcessor の順序に注意）
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    // OpenAPI / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // JSON ログ出力（本番用）
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // TOTP (RFC 6238)
    implementation("com.eatthepath:java-otp:0.4.0")

    // WebAuthn4J
    implementation("com.webauthn4j:webauthn4j-core:0.28.4.RELEASE")

    // WebP 画像変換（ImageIO SPI — サムネイル・アップロード画像のWebP出力用）
    implementation("org.sejda.imageio:webp-imageio:0.1.6")

    // Cloudflare R2（S3互換 API）+ AWS SES
    // R2 は S3 SDK でアクセスするため cloudfront 依存は不要
    implementation(platform("software.amazon.awssdk:bom:2.29.45"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sesv2")

    // User-Agent パース（F12.4 セッション管理）
    implementation("com.github.ua-parser:uap-java:1.6.1")

    // MySQL
    runtimeOnly("com.mysql:mysql-connector-j")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

    // === F12.1 PDF生成共通基盤 ===
    // Thymeleaf: PDF用HTMLテンプレートエンジン（画面描画には使わない）
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // Flying Saucer: HTML/CSS → PDF変換（OpenPDFバックエンド、iText非依存）
    implementation("org.xhtmlrenderer:flying-saucer-pdf-openpdf:9.4.0")
    // OpenPDF: 低レベルPDF操作（オーバーレイ等）
    implementation("com.github.librepdf:openpdf:2.0.3")
    // Apache Batik: SVG→PNG変換（電子印鑑の描画に使用）
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17")
    implementation("org.apache.xmlgraphics:batik-codec:1.17")

    // PDF内容検証用（テストスコープのみ）
    testImplementation("org.apache.pdfbox:pdfbox:3.0.3")

    // === Markdown → HTML 変換 ===
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")

    // === Stripe Connect 決済 ===
    implementation("com.stripe:stripe-java:28.2.0")

    // === HTTP クライアント（Claude API 等の外部 API 呼び出し） ===
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // === RSS/Atom フィード生成（ROME） ===
    implementation("com.rometools:rome:2.1.0")

    // ShedLock
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.2.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.2.0")

    // === QRコード生成（ZXing） ===
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // === Bucket4j レート制限（F12.5 エラーレポート） ===
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // === Caffeine キャッシュ（F11.1 SyncRateLimitFilter の TTL 付きバケット保持） ===
    // ConcurrentHashMap は Eviction がなく長期稼働で OOM を招くため、
    // expireAfterAccess + maximumSize で自動淘汰されるキャッシュに差し替える。
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // === HTML サニタイズ（F02.5 publish-daily extra_comment 用。将来 F04.1 統合検討） ===
    implementation("org.jsoup:jsoup:1.18.1")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Windows での JVM クラッシュ（0xC0000005）対策: 十分なヒープを確保し G1GC を明示指定
    jvmArgs(
        "-Xmx1g",
        "-XX:+UseG1GC",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=logs/heap-dump.hprof"
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
    // テスト数が 180+ の SpringBootTest を含み、累積でヒープが膨らむ。
    // 2g では CI で OOM（mysql-cj-abandoned-connection-cleanup スレッドからの OutOfMemoryError）が発生したため 3g に引き上げ。
    maxHeapSize = "3g"
    // 100 テストごとに JVM を fork し直し、累積メモリ（特に MySQL Connector の AbandonedConnectionCleanup が
    // WeakReference 監視している放置 Connection オブジェクトの累積）をリセットする。
    // forkEvery を入れないと全 ~1500 テストを 1 JVM で走らせるため、後半でヒープが枯渇する。
    setForkEvery(100L)
    // GC を明示し OOM 時にヒープダンプを残す（CI で再発時の調査用）
    //
    // -Dcom.mysql.cj.disableAbandonedConnectionCleanup=true:
    //   MySQL Connector/J の AbandonedConnectionCleanupThread を無効化する。
    //   このスレッドは「close() を呼ばずに参照を捨てた Connection」を WeakReference で監視し
    //   GC 後に物理 close するためのものだが、HikariCP は必ず Connection.close() を保証するため
    //   本来クリーンアップ対象は発生しない。にもかかわらず Connection 取得のたびに WeakReference
    //   が内部 Set に積まれ続け、GC 跡地が AbandonedConnectionCleanupThread の「mysql-cj-abandoned-
    //   connection-cleanup」スレッド由来の OutOfMemoryError を引き起こす（実際に CI で 27 分の沈黙
    //   後に発生）。本フラグで cleanup スレッド自体の起動を抑止し、Hikari に Connection ライフサイクル
    //   を完全に委譲する。これは MySQL Connector/J 公式の HikariCP 連携推奨設定。
    //
    // -Duser.timezone=Asia/Tokyo:
    //   テスト JVM のデフォルトタイムゾーンを Asia/Tokyo に明示固定する。
    //   forkEvery(100L) でフレッシュ JVM が起動するたび、CI ランナー (UTC) のデフォルト TZ が
    //   採用され、JDBC URL の serverTimezone=Asia/Tokyo と不整合となり、LocalDate が 1 日ずれる
    //   問題が発生していた（ShiftBudgetAllocationRepositoryTest で expected 2026-06-01 / but was
    //   2026-05-31）。JVM 側で TZ を JST に固定することで JDBC との一貫性を保証し、テスト結果が
    //   実行時刻・実行環境（ローカル/CI）に依存しないようにする。
    jvmArgs(
        "-XX:+UseG1GC",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/heap-dump.hprof",
        "-Dcom.mysql.cj.disableAbandonedConnectionCleanup=true",
        "-Duser.timezone=Asia/Tokyo"
    )
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        // 失敗時に完全スタックトレースを出力する。CI ログのみで NPE 起源を追跡できるようにする。
        showStandardStreams = false
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showExceptions = true
        showCauses = true
    }
}

tasks.jacocoTestReport {
    reports {
        csv.required = true
    }
}

// MapStruct: componentModel = "spring" をデフォルトに
// encoding: Javadoc コメント内の日本語文字（全角句点等）を正しく処理するため UTF-8 を明示指定
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Amapstruct.defaultComponentModel=spring",
        "-Amapstruct.unmappedTargetPolicy=ERROR",
        "-parameters"
    ))
}
