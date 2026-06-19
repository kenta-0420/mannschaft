plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.13"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
    // OWASP Dependency-Check: 依存ライブラリの既知 CVE をスキャンする（週次 CI で使用）
    id("org.owasp.dependencycheck") version "12.1.3"
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
    // F10.5 Phase 10-α: Micrometer Prometheus registry — /actuator/prometheus 公開と
    // http.server.requests / cache.gets / hikaricp.* 等の自動計測点を有効化
    implementation("io.micrometer:micrometer-registry-prometheus")
    // === OpenTelemetry トレーシング（F10.5 Phase 10-β: 分散トレーシング） ===
    // Micrometer Tracing Bridge: Spring Boot の @Observed / TraceContext を OTel に接続
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // OpenTelemetry OTLP エクスポーター: Jaeger/Tempo 等へ gRPC でスパンを送信
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
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
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

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

    // === spring-cloud-aws SQS（F09.6 Phase 8a: SES バウンス/苦情通知の SQS リスナー化） ===
    // SES → SNS Topic → SQS Queue → @SqsListener の受信入口。HTTP webhook を廃止し
    // SQS 内部認証（AWS SigV4）に切り替えることで SNS 署名検証コードが不要になる。
    // BOM 3.3.x は Spring Boot 3.5 系・AWS SDK v2（既存 software.amazon.awssdk:bom）と
    // 共存可能（spring-cloud-aws は AWS SDK v2 上に構築されている）。
    // starter-sqs は io.awspring.cloud:sqs（@SqsListener / SqsTemplate）を引き込む。
    implementation(platform("io.awspring.cloud:spring-cloud-aws-dependencies:3.3.0"))
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")

    // User-Agent パース（F12.4 セッション管理）
    implementation("com.github.ua-parser:uap-java:1.6.1")

    // MySQL
    runtimeOnly("com.mysql:mysql-connector-j")

    // H2: openapi-gen プロファイルで MySQL なしの generateOpenApiDocs タスクを実行するためのインメモリDB
    runtimeOnly("com.h2database:h2")

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

    // === F09.13 Phase 1-γ Excel生成共通基盤（Apache POI） ===
    // SXSSFWorkbook によるストリーミング生成で大量レコード（〜20,000件）に対応
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // === Markdown → HTML 変換 ===
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")

    // === Stripe Connect 決済 ===
    // ⚠️ 28.x 固定。29.x(basil)以降は invoice.application_fee_amount / transfer_data / charge 等が
    //    新 Invoice Payments 構造へ移行して invoice から消え、P5 継続課金の「invoice.created draft 窓で
    //    application_fee_amount を固定上書きする手数料機構」が黙殺で壊れる（HTTP 200 で無視される）。
    //    PoC 2026-06-05 実証（API バージョン 2025-02-24.acacia で成立・basil 系で黙殺を確認）。
    //    詳細: docs/features/F08.9_membership_billing_paywall/README §11-3 / scripts/poc/README_f089_p5_poc.md §0。
    //    更新時は P5 invoice 上書き機構の再設計（新 Invoice Payments 構造への移行）が必須。
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

    // === F04.3 PWA Push: VAPID署名 + Web Push HTTP送信 ===
    // web-push-java: VAPID鍵ペア署名・暗号化ペイロード送信の実装ライブラリ
    // bcprov: BouncyCastle暗号プロバイダー（web-push-javaが内部で使用するEC鍵操作の依存）
    implementation("nl.martijndwars:web-push:5.1.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // === F08.8 Phase 2: Resilience4j Bulkhead（シミュレーション計算の同時実行制限） ===
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-bulkhead:2.2.0")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Windows での JVM クラッシュ（0xC0000005）対策: 十分なヒープを確保し G1GC を明示指定
    //
    // -Dcom.mysql.cj.disableAbandonedConnectionCleanup=true:
    //   MySQL Connector/J の AbandonedConnectionCleanupThread を無効化する。
    //   HikariCP は Connection.close() を必ず保証するため cleanup 対象は発生しないが、
    //   Connection 取得のたびに WeakReference が積まれ続け、~25分後に cleanup スレッドで
    //   OOM → JVM クラッシュ（0xC0000005）が発生する。tests JVM と同様に抑止する。
    jvmArgs(
        "-Xmx1g",
        "-XX:+UseG1GC",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=logs/heap-dump.hprof",
        "-Dcom.mysql.cj.disableAbandonedConnectionCleanup=true"
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
    // テスト数が 180+ の SpringBootTest を含み、累積でヒープが膨らむ。
    // 2g → 3g（OOM 対策） → 4g（F09.13 Phase 1-γ: Apache POI 5.2.5 導入による Jackson Mixin OOM 対策）。
    // POI は内部で大量の XSD スキーマをロードしてヒープ・メタスペースを圧迫する。
    // ubuntu-latest は 7GB RAM。G1GC と SoftRef 積極解放で長時間テストのヒープ枯渇を防ぐ。
    maxHeapSize = "4g"
    // 100 テストごとに JVM を fork し直し、累積メモリ（特に MySQL Connector の AbandonedConnectionCleanup が
    // WeakReference 監視している放置 Connection オブジェクトの累積）をリセットする。
    // forkEvery を入れないと全 ~1500 テストを 1 JVM で走らせるため、後半でヒープが枯渇する。
    // ローカル（WSL2 Docker）環境では -Pfork.every=0 で無効化し、1コンテナ共有で高速化できる。
    setForkEvery((project.findProperty("fork.every") as String?)?.toLong() ?: 100L)
    // ローカル（WSL2 Docker）環境では Testcontainers の並列コンテナ起動が WSL2 ポートミラーリングの
    // タイミング問題を引き起こすため、-Pmax.parallel.forks=1 で上書きできるようにする。
    // CI 環境ではデフォルト 2 のまま動作する。
    maxParallelForks = (project.findProperty("max.parallel.forks") as String?)?.toInt() ?: 2
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

    // （旧: D-4 CrossDomainForeignKeyArchTest の baseline 再凍結スイッチ archunit.fk.refreeze を
    //  ここで伝播していたが、クロスドメイン FK 全廃 [158→0 件] 達成に伴い番人を baseline 方式から
    //  「1 件でも検出したら fail」する恒久 ArchRule へ格上げしたため不要になり撤去した。）

    // =====================================================================
    // CI テストシャーディング（backend CI 高速化②）
    // ---------------------------------------------------------------------
    // -Pshard.total=N -Pshard.index=i（0 始まり）を受け取り、テストクラスを
    // 「完全修飾クラス名の安定ハッシュ % total == index」で機械的に N 分割する。
    //
    // - プロパティ未指定時は何も除外しない＝全テスト実行（現状動作を完全維持）。
    // - GitHub Actions の matrix で各 shard を別ランナーへ割り当て、壁時計を縮める。
    // - クラス名ベースのため、同一クラス内の @Test／@Nested は必ず同じ shard に
    //   入る（Testcontainers の Spring コンテキスト共有・@DirtiesContext の整合を壊さない）。
    // - ハッシュは String.hashCode()（JDK 間で仕様安定）を Int.MIN_VALUE 対策で
    //   Long 化し正規化する決定論的関数。実行環境・実行順に依存しない。
    //
    // exclude { FileTreeElement } はテスト候補 .class ファイルごとに呼ばれる。
    // path 例: "com/mannschaft/app/user/UserServiceTest.class"
    //   → 末尾 ".class" を除き "/" を "." に変換して完全修飾名を得る。
    //   → ネストクラス（"Foo$Bar.class"）はトップレベルと同じ shard に乗せるため、
    //     '$' より前のトップレベル名でハッシュする（取りこぼし・分断を防ぐ）。
    // =====================================================================
    val shardTotal = (project.findProperty("shard.total") as String?)?.toIntOrNull()
    val shardIndex = (project.findProperty("shard.index") as String?)?.toIntOrNull()
    if (shardTotal != null && shardIndex != null && shardTotal > 1) {
        require(shardIndex in 0 until shardTotal) {
            "shard.index ($shardIndex) は 0..${shardTotal - 1} の範囲でなければならない（shard.total=$shardTotal）"
        }
        logger.lifecycle("[shard] テストを $shardTotal 分割し index=$shardIndex のみ実行する")
        exclude { element ->
            // ディレクトリは除外判定対象外（false=含める）
            if (element.isDirectory) return@exclude false
            val path = element.path
            if (!path.endsWith(".class")) return@exclude false
            // 完全修飾クラス名へ復元（トップレベル名のみでシャード判定）
            val fqcnTopLevel = path
                .removeSuffix(".class")
                .replace('/', '.')
                .substringBefore('$')
            // String.hashCode を Long 化して非負正規化 → 安定・決定論的
            val bucket = ((fqcnTopLevel.hashCode().toLong() and 0xFFFFFFFFL) % shardTotal).toInt()
            // 自分の shard 以外を除外（true=除外）
            bucket != shardIndex
        }
    }

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

// F09.14 Phase 3-B: 重要事項説明書 Word テンプレート docx 生成タスク。
// `./gradlew generateDisclosureWordTemplates` で 6 種の docx を
// src/main/resources/docx/disclosure/ および src/test/resources/docx/disclosure/ に出力する。
// テンプレ更新時に再生成して git にコミットすること。
tasks.register<JavaExec>("generateDisclosureWordTemplates") {
    group = "build setup"
    description = "F09.14 Phase 3-B 用 重要事項説明書 Word テンプレート docx を 6 種出力する"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.mannschaft.app.disclosure.support.WordTemplateGenerator")
    workingDir = projectDir
    dependsOn("compileTestJava")
}

// F02.10 Phase 3: GeoNames Postal Codes 取り込みタスク。
// 設計書 §10.3 / docs/features/F02.10_weather_widget.md
// 使い方:
//   ./gradlew importPostalCodes
//   ./gradlew importPostalCodes --args="--country=ALL"
//   ./gradlew importPostalCodes --args="--country=JP"
// 仕組み:
//   weather-import プロファイルで Spring Boot Application を起動し、
//   PostalCodesImportRunner が GeonamesImportService.importAll を呼び出す。
//   完了後 ApplicationContext を閉じて JVM を終了する。
tasks.register<JavaExec>("importPostalCodes") {
    group = "application"
    description = "GeoNames Postal Codes（allCountries.zip）を取り込み postal_codes テーブルへ upsert する"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.mannschaft.app.MannschaftApplication")
    workingDir = projectDir
    // 既定で weather-import プロファイルを有効化
    systemProperty("spring.profiles.active", "weather-import")
    // ヒープと GC は bootRun と同じ
    jvmArgs(
        "-Xmx1g",
        "-XX:+UseG1GC",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=logs/heap-dump.hprof"
    )
    dependsOn("compileJava")
}

// =============================================================================
// OWASP Dependency-Check 設定
// =============================================================================
// 使い方: ./gradlew dependencyCheckAnalyze
// 週次 CI (.github/workflows/security-scan.yml) で自動実行される。
// NVD API キーは環境変数 NVD_API_KEY で設定する（GitHub Secret: NVD_API_KEY）。
// スキャン対象: runtimeClasspath のみ（テスト専用ライブラリは除外）。
// CVSS スコア 7.0 以上 (HIGH) で CI を失敗させる。
// =============================================================================
dependencyCheck {
    // CVSS スコア閾値: 7.0 以上（HIGH/CRITICAL）で失敗
    failBuildOnCVSS = 7.0f

    // HTML + JSON レポートを出力（CI Artifact に保存する）
    formats = listOf("HTML", "JSON")

    // NVD API キー（CI では環境変数 NVD_API_KEY から取得）
    // ローカルで動かす場合は gradle.properties か環境変数で NVD_API_KEY を設定すること
    nvd {
        apiKey = System.getenv("NVD_API_KEY") ?: ""
        // API レート制限: 無料枠は 5 req/30s。余裕を持って 4000ms 間隔
        delay = 4000
    }

    // テスト専用ライブラリはスキャン対象から除外（本番デプロイされないため）
    scanConfigurations = listOf("runtimeClasspath")

    // 誤検知（false positive）の抑制ファイル
    // suppressionFile = "${projectDir}/owasp-suppressions.xml"

    // analyzers: 不要なアナライザを無効化してスキャン高速化
    analyzers {
        // Node.js の npm は frontend/ ディレクトリで別管理なので無効
        nodeEnabled = false
        nodeAuditEnabled = false
        // Nuget は使っていないので無効
        nuspecEnabled = false
        nugetconfEnabled = false
        // Ruby も使っていないので無効
        bundleAuditEnabled = false
    }
}

// OpenAPI JSON 静的生成
// `./gradlew generateOpenApiDocs` で docs/openapi.json を生成する。
// springdoc-openapi-gradle-plugin はプロジェクトをフォークした Spring Boot プロセスとして起動し、
// /v3/api-docs エンドポイントから JSON を取得して outputDir に保存する。
// openapi-gen プロファイル: MySQL 不要、H2 インメモリ DB + Flyway 無効 で起動する。
openApi {
    // 8082 ポートを使用: dev サーバー(:8080)が稼働中でも競合しない
    apiDocsUrl.set("http://localhost:8082/v3/api-docs")
    // projectDir は backend/ ディレクトリを指すため、親（リポジトリルート）の docs/ を指定する
    outputDir.set(file("${projectDir.parentFile}/docs"))
    outputFileName.set("openapi.json")
    // フォーク先 Spring Boot が完全起動するまで待機する秒数
    // ddl-auto:create-drop + 全 Entity 構築で 5 分前後かかるため、余裕を持って 300 秒に設定
    // （CI 運用は廃止しローカル生成のみのため、長めの待機で問題ない）
    waitTimeInSeconds.set(300)
    customBootRun {
        // args.add は springdoc-openapi-gradle-plugin では機能しないため jvmArgs で -D オプションを使用する
        jvmArgs.add("-Dspring.profiles.active=openapi-gen")
        jvmArgs.add("-Dserver.port=8082")
        // MapProperty.put() で systemProperties にも設定し二重に適用する
        systemProperties.put("spring.profiles.active", "openapi-gen")
        systemProperties.put("server.port", "8082")
        // フォークプロセスのクラスパスを最新コンパイル済みの sourceSets.main.runtimeClasspath に明示固定する。
        // プラグインは customBootRun.classpath が空の場合に bootRun.classpath をフォールバックで使うが、
        // それは Gradle build cache から復元されたクラスパス解決に依存しているため、
        // build cache が stale なエントリを返した場合に古いクラスを参照するリスクがある。
        // sourceSets["main"].runtimeClasspath を直接指定することで、
        // compileJava の出力（build/classes/java/main）を常に参照することを保証する。
        classpath.from(sourceSets["main"].runtimeClasspath)
    }
}

// generateOpenApiDocs タスクの up-to-date 誤判定防止と依存関係の明示
//
// 問題の背景（#1547 で判明）:
//   OpenApiGeneratorTask には @InputFiles 等の Gradle タスク入力アノテーションがなく、
//   output ファイル（openapi.json）の存在だけで up-to-date と判断される。
//   ソースコードを変更しても openapi.json が存在する限りタスクがスキップされ、
//   最新 API が反映されない「古いキャッシュ参照」状態になる。
//
// 根治方針:
//   1. outputs.upToDateWhen { false } で常に再実行を強制
//      → ソース変更の有無に関わらず /v3/api-docs を再取得して最新 JSON を生成する
//   2. dependsOn("classes") で compileJava → classes を先行実行を明示保証
//      → フォークプロセスが最新コンパイル済みクラスを使うことを確実にする
tasks.named("generateOpenApiDocs") {
    // 常に再実行: openapi.json が存在しても up-to-date と判断させない
    outputs.upToDateWhen { false }
    // 最新コンパイル結果を先行保証（プラグインが内部で設定しているが明示して二重保証）
    dependsOn("classes")
}
