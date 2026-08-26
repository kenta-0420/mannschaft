import java.util.concurrent.ConcurrentHashMap

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

// =============================================================================
// ShardAssignment: CI テストシャード振り分けの単一実装（実行フィルタと検証タスクで共用）
// -----------------------------------------------------------------------------
// 【なぜ object に一本化するか（改訂履歴あり）】
//   第1版: 「実際にテストを除外するロジック」と「検証タスク（verifyShardCoverage）の
//   割当計算ロジック」が別実装で存在し、sum==総数 が構造的に自明な偽 green だった。
//
//   第2版（本版）: 第1版の是正で isIncluded() を共用関数化したが、実行側は
//   「path 正規化 → isIncluded 呼び出し → 結果を外側で ! 反転」という組み立てを
//   Test.exclude { } 側にまだ残していたため、正規化のバグや ! の反転そのものは
//   検証タスク側の別実装（自前で FQCN を復元）では検出できなかった。
//   本版では **shouldExcludeForShard() / shouldExcludeMigrationReplay() が
//   「パス正規化 → bucket 判定 → 否定」を丸ごと1つの関数に含み、
//   Test.exclude { } はこの関数の戻り値をそのまま返すだけ**にする。
//   verifyShardCoverage もこの同じ関数を、実際にコンパイルされた .class ファイルの
//   生パス文字列に対して直接呼ぶ。これにより正規化・反転のどちらが壊れても
//   実行側・検証側が同じ壊れ方を共有し、検証が確実に fail する。
// =============================================================================
object ShardAssignment {
    /** 安定ハッシュ関数（重み表に無いクラスのフォールバック用） */
    fun hashBucket(fqcnTopLevel: String, shardTotal: Int): Int =
        ((fqcnTopLevel.hashCode().toLong() and 0xFFFFFFFFL) % shardTotal).toInt()

    /** shard-weights.properties を読み込む（存在しなければ空 map） */
    fun loadWeights(weightsFile: File): Map<String, Double> {
        if (!weightsFile.exists()) return emptyMap()
        return weightsFile.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
                key to value
            }
            .toMap()
    }

    /**
     * 貪欲法（Longest Processing Time first）で重み表のクラスを shard へ割り付ける。
     * 重い順にソートし、その時点で合計時間が最小の shard へ割り当てる
     * （理論上界は最適解の 4/3 倍）。weights が空なら空 map を返す
     * （＝呼び出し側は shouldExcludeForShard() で自動的に安定ハッシュへフォールバックする）。
     *
     * 【呼び出し側の責務】渡す weights は「実際にその CI 実行で走るクラス集合」に
     * 限定すること（-PexcludeMigrationTests=true の場合は migration+Flyway クラスを
     * 事前に除いた weights を渡す）。除外予定のクラスまで貪欲法の対象に含めると、
     * それらが消費する「重み予算」が実行対象クラスの均等化を妨げ、実際に走る
     * プロファイルの偏りが悪化する（migration 込みの重みだけで最適化すると、
     * migration 除外時＝大多数の通常 PR で逆に不均等化する回帰を招く）。
     */
    fun buildGreedyAssignment(weights: Map<String, Double>, shardTotal: Int): Map<String, Int> {
        if (weights.isEmpty()) return emptyMap()
        val shardTotals = DoubleArray(shardTotal)
        val assignment = LinkedHashMap<String, Int>()
        weights.entries
            .sortedByDescending { it.value }
            .forEach { (fqcn, seconds) ->
                var minIdx = 0
                for (i in 1 until shardTotal) {
                    if (shardTotals[i] < shardTotals[minIdx]) minIdx = i
                }
                assignment[fqcn] = minIdx
                shardTotals[minIdx] += seconds
            }
        return assignment
    }

    /** 与えられたトップレベル FQCN が属する shard index（0..shardTotal-1）を決定する。 */
    private fun bucketOf(fqcnTopLevel: String, shardTotal: Int, weightedBucketOf: Map<String, Int>): Int =
        weightedBucketOf[fqcnTopLevel] ?: hashBucket(fqcnTopLevel, shardTotal)

    /** .class ファイルの相対 path（"/" 区切り）からトップレベル FQCN を復元する。 */
    fun fqcnTopLevelFromClassPath(path: String): String =
        path.removeSuffix(".class").replace('/', '.').substringBefore('$')

    /**
     * 【単一の述語関数・その1】CI テストシャード分割の exclude 判定。
     * パス正規化 → bucket 判定 → 否定までを丸ごと含む。build.gradle.kts の
     * Test.exclude { } はこの関数の戻り値をそのまま返すだけにし、外側で
     * 追加の "!" 等を書かない（別実装・反転漏れの温床を断つ）。
     * verifyShardCoverage も実クラスファイルの生パスに対して直接この関数を呼ぶ。
     */
    fun shouldExcludeForShard(
        path: String,
        isDirectory: Boolean,
        shardIndex: Int,
        shardTotal: Int,
        weightedBucketOf: Map<String, Int>
    ): Boolean {
        if (isDirectory) return false
        if (!path.endsWith(".class")) return false
        val fqcnTopLevel = fqcnTopLevelFromClassPath(path)
        return bucketOf(fqcnTopLevel, shardTotal, weightedBucketOf) != shardIndex
    }

    /** Flyway マイグレーション再生テストか（"migration" パッケージ配下 かつ クラス名に Flyway を含む）。 */
    fun isMigrationReplayClass(fqcnTopLevel: String): Boolean {
        val simpleName = fqcnTopLevel.substringAfterLast('.')
        return fqcnTopLevel.contains(".migration.") && simpleName.contains("Flyway")
    }

    /**
     * 【単一の述語関数・その2】Flyway マイグレーション再生テストの exclude 判定。
     * -PexcludeMigrationTests=true のときに使う。こちらもパス正規化を関数内に含み、
     * Test.exclude { } と verifyShardCoverage の両方が直接呼ぶ。
     */
    fun shouldExcludeMigrationReplay(path: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        if (!path.endsWith(".class")) return false
        return isMigrationReplayClass(fqcnTopLevelFromClassPath(path))
    }
}

/** verifyShardCoverage が扱う「コンパイル済み .class ファイル1件」の生パスとトップレベルFQCN。 */
class ShardCoverageClassFile(val path: String, val topLevelFqcn: String)

tasks.withType<Test> {
    // =====================================================================
    // @Tag("perf") 運用（β4 fan-out 実測 IT の分離）
    // ---------------------------------------------------------------------
    // 重量級の実測 IT（合成1万人 fan-out・出欠1万・投稿1万）は CI smoke に載せると
    // 壁時計を大きく食うため、JUnit タグ "perf" を付けて通常の `test` からは除外する。
    // 専用タスク `perfTest`（下で register）だけが includeTags("perf") で実行する。
    // タグ filter はタスク名で分岐して 1 箇所に閉じ、useJUnitPlatform の呼び出し順に
    // 依存しない決定論的な構成にする。
    // =====================================================================
    val isPerfTask = name == "perfTest"
    useJUnitPlatform {
        if (isPerfTask) {
            includeTags("perf")
        } else {
            excludeTags("perf")
        }
    }
    // テスト数が 180+ の SpringBootTest を含み、累積でヒープが膨らむ。
    // 2g → 3g（OOM 対策） → 4g（F09.13 Phase 1-γ: Apache POI 5.2.5 導入による Jackson Mixin OOM 対策）。
    // POI は内部で大量の XSD スキーマをロードしてヒープ・メタスペースを圧迫する。
    // ubuntu-latest は 7GB RAM。G1GC と SoftRef 積極解放で長時間テストのヒープ枯渇を防ぐ。
    maxHeapSize = "4g"
    // N テストごとに JVM を fork し直し、累積メモリ（特に MySQL Connector の AbandonedConnectionCleanup が
    // WeakReference 監視している放置 Connection オブジェクトの累積）をリセットする。
    //
    // 【100 → 500 に緩和した理由（CMP-045・CI shard の 60 分打ち切り根治）】
    // 既定値 100 は「CI 全テスト OOM 根治」（2026-05-07）で導入されたが、同じコミットで入れた
    // -Dcom.mysql.cj.disableAbandonedConnectionCleanup=true が OOM の真因（cleanup スレッドの
    // WeakReference 累積）を潰しており、forkEvery はコミットメッセージ自身が「万一漏れがあった
    // 場合の補助的な堤」と書いているとおり二重の保険だった。その保険の代償が桁違いに大きい:
    //
    //   - AbstractMySqlIntegrationTest（継承 348 クラス）の singleton コンテナは
    //     「JVM 内でしか singleton ではない」。JVM を捨てるたび static 初期化が再走し、
    //     withReuse(false) の MySQL コンテナが再起動、Spring TestContext キャッシュも全消滅する。
    //   - 対照実験（同一テスト・テスト実正味 23.3 秒で不変）: fork.every=0 → 390 秒 /
    //     fork.every=50 → 1285 秒。1 fork あたり +447 秒（保守見積でも +357 秒）。
    //   - 本番 shard4 は :test 41.96 分に対しテスト実正味 7.4 分。約 14 回の fork が
    //     34.6 分を食い潰しており、これが 60 分 timeout 打ち切りの支配項だった。
    //
    // 【なぜ 0 ではなく 500 か（ローカル実測 2026-08-15）】
    // Testcontainers を使う IT 184 クラス（2517 テスト・CI 1 shard 相当）を fork.every=0 /
    // maxParallelForks=2 で完走させたところ OOM もヒープダンプも発生しなかったが、
    // 各ワーカー JVM のヒープは 4g 上限に対し 3.9g 使用まで張り付いた（G1 が辛うじて維持）。
    // ubuntu-latest は 7GB RAM で 4g × 2 fork が同時に上限へ達すると OS 側で詰む。
    // よって「fork しない」ではなく「1 JVM あたりのテスト数を 5 倍に緩める」を採る。
    // 500 なら 1 shard あたりの fork 回数が約 14 → 約 3 に減り固定コストの大半を落としつつ、
    // 実測で安全域だった水準の半分以下でヒープを定期リセットできる。
    // ※ この値を 100 に戻すと CI shard は再び 60 分打ち切りに戻る。変更時は必ず再実測すること。
    //
    // 【500 → 180 に是正（すべて CI 実走の実測にもとづく・2026-08-26）】
    //
    // 最重要: forkEvery は【1 ワーカーが処理したテストクラス数】で数える。
    // maxParallelForks=2 なら 1 ワーカーの担当は shard 全体（350〜384 クラス）の約半分であり、
    // 【shard 全体のクラス数と forkEvery を直接比べてはならない】。
    // この一点を見落として値を二度外した（190 / 200）。以後は必ず計測器の実数で確かめること。
    //
    // 本ファイルの計測器が CI ログへ出す "[test-jvm] 起動したテスト JVM 数" の実測値:
    //
    //   forkEvery | 起動した JVM 数 (shard0..5) | shard 壁時計 平均 / 最長
    //   ----------+-----------------------------+--------------------------
    //   500(旧)   | 4  4  4  4  4  4            | 41.9 分 / 46.8 分
    //   90        | 14 16 14 14 16 14           | 56.0 分 / 59.2 分  ← 60分打切りに余裕なし
    //   180(採用) | （下記 PR の実測を参照）      |
    //
    // 90 は JVM 数こそ増えるが最長 59.2 分で【打ち切り 60 分にほぼ接している】ため採れない。
    // 上記2点から「JVM 1 個増あたり約 1.28 分」を得て、基準値 4 の約2倍を狙える 180 を採る。
    // 目的は「ヒープを実際にリセットさせること」であって fork 回数の最大化ではない。
    //
    // ※ 値を変えたら【必ず CI ログの "[test-jvm]" と shard 壁時計の両方】を見ること。
    //   クラス数だけを見て緩めると、また実際の JVM 数が増えないまま「直したつもり」になる。
    //
    // ローカル（WSL2 Docker）環境では -Pfork.every=0 で無効化し、1コンテナ共有で高速化できる。
    // perfTask は単一クラスの重量級 IT ゆえ forkEvery=0（1 JVM 共有）で無駄な再 fork を避ける。
    setForkEvery(if (isPerfTask) 0L else ((project.findProperty("fork.every") as String?)?.toLong() ?: 180L))
    // ローカル（WSL2 Docker）環境では Testcontainers の並列コンテナ起動が WSL2 ポートミラーリングの
    // タイミング問題を引き起こすため、-Pmax.parallel.forks=1 で上書きできるようにする。
    // CI 環境ではデフォルト 2 のまま動作する。
    // perfTask は単一クラスのため並列 fork しない（Testcontainer/測定の相互干渉を避ける）。
    maxParallelForks =
        if (isPerfTask) 1 else ((project.findProperty("max.parallel.forks") as String?)?.toInt() ?: 2)
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
    // 【テスト JVM 数の計測器（実測 2026-08-26）】
    // forkEvery が実際に発火したかを「計算」で二度続けて外したため、回数そのものを数える。
    //
    // Gradle はワーカー JVM ごとに "Gradle Test Executor N" という名前のスイートを1つ作る。
    // その名前を beforeSuite で集めれば、【実際に起動したテスト JVM の個数】がそのまま得られる
    // （テスト側に依存も追加コードも要らない。junit-platform-launcher は test の
    //  コンパイルクラスパスに無いため、JUnit のリスナー実装では計測できなかった）。
    //
    // 出力は root スイートの afterSuite で行うので、テストが失敗した回でも必ず出る
    // （OOM で落ちた回こそ、この数字が要る）。CI ログを "[test-jvm]" で grep せよ。
    // beforeSuite は複数ワーカーのイベントを受けるため、スレッド安全な集合を使う。
    val testJvmNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    addTestListener(object : org.gradle.api.tasks.testing.TestListener {
        override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) {
            if (suite.name.startsWith("Gradle Test Executor")) {
                testJvmNames.add(suite.name)
            }
        }
        override fun afterSuite(
            suite: org.gradle.api.tasks.testing.TestDescriptor,
            result: org.gradle.api.tasks.testing.TestResult
        ) {
            if (suite.parent == null) {
                logger.lifecycle(
                    "[test-jvm] 起動したテスト JVM 数 = ${testJvmNames.size}" +
                        "（forkEvery=${forkEvery} / maxParallelForks=${maxParallelForks}）" +
                        " ※ forkEvery はワーカー単位で数える"
                )
                logger.lifecycle("[test-jvm] 内訳: " + testJvmNames.joinToString(", "))
            }
        }
        override fun beforeTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor) {}
        override fun afterTest(
            testDescriptor: org.gradle.api.tasks.testing.TestDescriptor,
            result: org.gradle.api.tasks.testing.TestResult
        ) {}
    })

    // 【ヒープダンプはワーカーごとに分ける（実測 2026-08-26）】
    // 以前は -XX:HeapDumpPath=build/heap-dump.hprof という【固定ファイル名】だったため、
    // maxParallelForks=2 の 2 ワーカーが OOM 時に同一ファイルへ同時に書き込み、
    // 出来上がったダンプが 6.97GB になった。-Xmx4g のワーカー 1 個から 4g を超える
    // ダンプが出ることは原理的にあり得ず、この数字は【解析者を誤らせる嘘の値】である
    // （実際 shard5 の OOM 調査で「1 プロセスが 7GB 使った」という誤読を招き、
    //  真因（fork が 0 回でコンテキストが蓄積していたこと）の特定を遅らせた）。
    // ディレクトリを指定すると JVM が java_pid<PID>.hprof を各自作るため衝突しない。
    val heapDumpDir = project.layout.buildDirectory.dir("heap-dumps").get().asFile
    doFirst { heapDumpDir.mkdirs() }
    jvmArgs(
        "-XX:+UseG1GC",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=${heapDumpDir.absolutePath}",
        "-Dcom.mysql.cj.disableAbandonedConnectionCleanup=true",
        "-Duser.timezone=Asia/Tokyo"
    )

    // （旧: D-4 CrossDomainForeignKeyArchTest の baseline 再凍結スイッチ archunit.fk.refreeze を
    //  ここで伝播していたが、クロスドメイン FK 全廃 [158→0 件] 達成に伴い番人を baseline 方式から
    //  「1 件でも検出したら fail」する恒久 ArchRule へ格上げしたため不要になり撤去した。）

    // =====================================================================
    // -PexcludeMigrationTests の値を先に読む（下記シャード割当ロジックが、
    // 「実際にこの実行で走るクラス集合」に対して貪欲法を適用するために必要）。
    // 意味・背景は下の「Flyway マイグレーション再生テストの条件付き除外」節を参照。
    // =====================================================================
    val excludeMigrationTests =
        (project.findProperty("excludeMigrationTests") as String?)?.toBoolean() ?: false

    // =====================================================================
    // CI テストシャーディング（backend CI 高速化② → 実行時間ベースの重み付け振り分けへ改良）
    // ---------------------------------------------------------------------
    // -Pshard.total=N -Pshard.index=i（0 始まり）を受け取り、テストクラスを N 分割する。
    //
    // 【振り分けアルゴリズム】
    //   backend/src/test/resources/shard-weights.properties（クラス単位の実測秒数、
    //   backend/scripts/regen-shard-weights.sh で生成）が存在する場合:
    //     貪欲法（bin packing の Longest Processing Time first）で決定論的に割り付ける。
    //     全トップレベルクラスを重い順にソートし、その時点で合計時間が最小の shard へ
    //     順に割り当てる。重み表に無いクラス（新規テスト等）は末尾に回し、
    //     従来の安定ハッシュ（String.hashCode % total）で shard を決める
    //     （＝重み表が古くても新規テストは必ずどれか 1 shard にだけ割り当たる）。
    //   重み表ファイル自体が存在しない場合:
    //     全クラスが従来の安定ハッシュ方式にフォールバックする（旧動作と完全に同じ）。
    //
    //   いずれの経路でも「1 クラス＝ちょうど 1 shard」が保証される
    //   （貪欲法・フォールバックとも関数は決定論的かつ全域で定義されるため、
    //    取りこぼし・重複割り当ては構造的に起こり得ない）。
    //
    // 【除外プロファイルごとの構成時計算（重要）】
    //   excludeMigrationTests=true の実行では、下の「Flyway マイグレーション再生テストの
    //   条件付き除外」フィルタにより migration+Flyway クラス（全体の実行時間の約8割）が
    //   別途まるごと除外される。もし貪欲法を「migration 込みの全クラス」に対して行うと、
    //   それらが消費する重み予算のせいで残りのクラスの均等化が崩れる
    //   （実測: migration込みで最適化した重み表を使うと、通常 PR＝migration除外時の
    //   6shard 実行時間が最大/最小比 2.19 倍まで悪化した。通常 PR がほとんどの経路である
    //   ため看過できない回帰）。そのため貪欲法は「この実行で実際に走るクラス集合」
    //   （excludeMigrationTests=true なら migration+Flyway を除いた残り）に対してのみ行う。
    //
    // - プロパティ未指定時は何も除外しない＝全テスト実行（現状動作を完全維持）。
    // - GitHub Actions の matrix で各 shard を別ランナーへ割り当て、壁時計を縮める。
    // - クラス名ベースのため、同一クラス内の @Test／@Nested は必ず同じ shard に
    //   入る（Testcontainers の Spring コンテキスト共有・@DirtiesContext の整合を壊さない）。
    // - ハッシュは String.hashCode()（JDK 間で仕様安定）を Int.MIN_VALUE 対策で
    //   Long 化し正規化する決定論的関数。実行環境・実行順に依存しない。
    //
    // exclude { FileTreeElement } には ShardAssignment.shouldExcludeForShard() の戻り値を
    // そのまま渡す（パス正規化・bucket 判定・否定を丸ごと関数内に閉じ込め、外側で
    // 追加の判定・反転を書かない。verifyShardCoverage も同じ関数を直接呼ぶ）。
    //
    // 【重み表の再生成】テストクラスが大幅に増減した時に
    //   backend/scripts/regen-shard-weights.sh を実行（詳細は同スクリプト参照）。
    // =====================================================================
    val shardTotal = (project.findProperty("shard.total") as String?)?.toIntOrNull()
    val shardIndex = (project.findProperty("shard.index") as String?)?.toIntOrNull()
    if (shardTotal != null && shardIndex != null && shardTotal > 1) {
        require(shardIndex in 0 until shardTotal) {
            "shard.index ($shardIndex) は 0..${shardTotal - 1} の範囲でなければならない（shard.total=$shardTotal）"
        }

        // 重み表の読み込み・貪欲法割り付け・判定は ShardAssignment（本ファイル冒頭で定義）に
        // 一本化されており、verifyShardCoverage タスクも同じ関数を呼ぶ（二重実装の禁止）。
        val weightsFile = file("src/test/resources/shard-weights.properties")
        val rawWeights = ShardAssignment.loadWeights(weightsFile)
        // 実際にこの実行で走るクラス集合に限定して貪欲法を適用する（上記コメント参照）。
        val weights = if (excludeMigrationTests) {
            rawWeights.filterKeys { fqcn -> !ShardAssignment.isMigrationReplayClass(fqcn) }
        } else {
            rawWeights
        }
        val weightedBucketOf = ShardAssignment.buildGreedyAssignment(weights, shardTotal)
        if (weights.isNotEmpty()) {
            val shardTotals = DoubleArray(shardTotal)
            weights.forEach { (fqcn, seconds) -> shardTotals[weightedBucketOf.getValue(fqcn)] += seconds }
            logger.lifecycle(
                "[shard] 重み表 ${weightsFile.name} を読み込み（excludeMigrationTests=$excludeMigrationTests で" +
                    "${rawWeights.size - weights.size}クラスを対象外） ${weights.size} クラスを貪欲法で割り付けた。" +
                    "shard 別合計(秒): " + shardTotals.mapIndexed { i, t -> "$i=${"%.1f".format(t)}" }.joinToString(", ")
            )
        } else {
            logger.lifecycle("[shard] 重み表が見つからないため全クラスを安定ハッシュへフォールバックする")
        }

        logger.lifecycle("[shard] テストを $shardTotal 分割し index=$shardIndex のみ実行する")
        exclude { element ->
            ShardAssignment.shouldExcludeForShard(element.path, element.isDirectory, shardIndex, shardTotal, weightedBucketOf)
        }
    }

    // =====================================================================
    // Flyway マイグレーション再生テストの条件付き除外（backend CI 高速化③）
    // ---------------------------------------------------------------------
    // -PexcludeMigrationTests=true を受け取ると、Flyway マイグレーション再生テスト
    // （V1 → 最新までの 150 本超を毎回リプレイして 1 件 assert するクラス群）を除外する。
    //
    // 【なぜ必要か】実測（PR #2380）で、これらが各 shard の計測テスト時間の
    //   72〜88% を占めていた。1 クラス＝テスト 1 件で 60〜113 秒かかる。
    //   一方これらが検証するのは「マイグレーション SQL 自体の正しさ」であり、
    //   db/migration/** が変わらない PR では結果が変わり得ない。
    //
    // 【抑制ではなく条件付き実行】db/migration/** を触る PR では CI 側が本プロパティ
    //   を渡さない＝従来どおり全件実行する。加えて backend-nightly-full.yml が
    //   毎晩フル実行し、除外分がどの PR でも走らないまま腐ることを防ぐ。
    //   （免罪符化の防止。memory: feedback_baseline_suppression_is_debt）
    //
    // 【セレクタ】「.migration. パッケージ配下」かつ「単純クラス名に Flyway を含む」
    //   の AND を取る（ShardAssignment.isMigrationReplayClass に集約）。防御的に AND
    //   とする理由:
    //   - パッケージのみだと MigrationPrimaryKeyConventionTest（規約 guard・コンテナ不要）
    //     や StoragePathMigrationBatchServiceTest（Mockito 単体テスト）まで巻き込む。
    //   - クラス名 Flyway のみだと FlywayTimestampNamingGuardTest（採番 guard・高速）や
    //     SharedFileLinkFlywayColumnIT / ProxyInputConsentS3KeyFlywaySchemaTest
    //     （こちらは @SpringBootTest を使う＝性質が異なる）まで巻き込む。
    //   AND により対象は約70クラス。大半が MySQLContainer を自前起動し、
    //   @SpringBootTest を使わない純 Flyway + 生 JDBC のテストであることを確認済み。
    //
    // exclude { FileTreeElement } には ShardAssignment.shouldExcludeMigrationReplay() の
    // 戻り値をそのまま渡す（上のシャードフィルタと同じ理由で、パス正規化・判定を
    // 関数内に閉じ込める。verifyShardCoverage も同じ関数を直接呼ぶ）。
    // =====================================================================
    if (excludeMigrationTests) {
        logger.lifecycle(
            "[migration] Flyway マイグレーション再生テストを除外する" +
                "（db/migration/** 無変更のため。フル実行は backend-nightly-full.yml が担保）"
        )
        exclude { element ->
            ShardAssignment.shouldExcludeMigrationReplay(element.path, element.isDirectory)
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

// =============================================================================
// perfTest: @Tag("perf") の重量級実測 IT だけを実行する専用タスク
// -----------------------------------------------------------------------------
// 使い方:
//   cd backend
//   ./gradlew perfTest -Pmax.parallel.forks=1
// 通常の `test`（CI smoke）は withType<Test> の excludeTags("perf") で perf を除外する。
// perfTest は includeTags("perf")（タスク名分岐で自動適用）で perf のみを走らせる。
// heap / jvmArgs / testLogging 等の共通設定は withType<Test> から継承する。
// =============================================================================
tasks.register<Test>("perfTest") {
    group = "verification"
    description = "@Tag(\"perf\") の β4 fan-out 実測 IT のみを実行する（測定専用・CI smoke からは分離）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // 通常の check/build には載せない（明示実行のみ）。
    shouldRunAfter(tasks.named("test"))
}

// =============================================================================
// verifyShardCoverage: CI テストシャード重み付け振り分けの取りこぼし検証タスク
// -----------------------------------------------------------------------------
// 【検証方法（重要・改訂版）】
// - 割当を独自に再計算するのではなく、実際にコンパイルされた .class ファイルの
//   **生の相対パス文字列**（Gradle の FileTreeElement.path と同一形式）に対して、
//   実運用と全く同じ ShardAssignment.shouldExcludeForShard() /
//   shouldExcludeMigrationReplay() を直接呼ぶ。正規化・bucket 判定・否定を
//   これらの関数の外で再実装しないため、正規化の破損や否定の反転が起きれば
//   検証も同じ壊れ方の影響を受けて必ず fail する。
// - shardIndex = 0..total-1 の「全パターン」へ実際に適用し、各 .class ファイルが
//   （migration 除外対象でない限り）ちょうど1つの shard で実行されることを確認する。
// - migration 除外プロファイル（excludeMigrationTests=true）では、migration+Flyway
//   クラスは「どの shard でも実行されない」ことこそが正しい仕様なので、そちらは
//   0 件であることを確認する（1件でも実行対象に残っていれば bug）。
// - 同一トップレベルクラスに属する複数 .class ファイル（ネストクラス）が同じ shard に
//   乗ることを、ShardAssignment を経由しない単純な文字列グルーピングで独立に検証する
//   （'$' 除去などの正規化が壊れて兄弟ネストクラスが別 shard へ割れる事故を検出する）。
// - 貪欲割当は「実際にそのプロファイルで走るクラス集合」に対して行う
//   （excludeMigrationTests=true なら migration+Flyway を除いた重みで貪欲法を実行する。
//    build.gradle.kts の Test.exclude 側と同じロジック）。
// - 4パターン（重み表あり/なし × migration除外あり/なし）全てを検証する。
//
// 使い方: cd backend && ./gradlew verifyShardCoverage -Pshard.total=6
// （testClasses への依存によりコンパイルは自動で行われる）
// =============================================================================
tasks.register("verifyShardCoverage") {
    group = "verification"
    description = "実際の Test.exclude 述語（ShardAssignment.shouldExcludeForShard 等）を、" +
        "コンパイル済み.classファイルの生パスと全shardIndexへ直接適用し、4プロファイルで取りこぼし・重複・" +
        "ネスト分断が無いことを検証する"
    dependsOn(tasks.named("testClasses"))
    doLast {
        val total = (project.findProperty("shard.total") as String?)?.toIntOrNull() ?: 6
        require(total > 1) { "shard.total は 2 以上を指定すること（現在: $total）" }

        // コンパイル済み .class ファイル全件の「生の相対パス」（posix "/" 区切り、".class" 付き）を集める。
        // ここで dedup や '$' 除去はしない（ネストクラスファイルも個別に保持し、後段の
        // sibling grouping で独立に検証するため）。
        val classesDirs = sourceSets["test"].output.classesDirs.files
        val classFiles = mutableListOf<ShardCoverageClassFile>()
        classesDirs.forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { f ->
                    val relPosix = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    val topLevelFqcn = ShardAssignment.fqcnTopLevelFromClassPath(relPosix)
                    classFiles.add(ShardCoverageClassFile(relPosix, topLevelFqcn))
                }
        }
        val topLevelCount = classFiles.map { it.topLevelFqcn }.distinct().size
        logger.lifecycle(
            "[verifyShardCoverage] コンパイル済み .class ファイル総数: ${classFiles.size} / " +
                "トップレベルクラス数: $topLevelCount"
        )

        val weightsFile = file("src/test/resources/shard-weights.properties")
        val rawWeights = ShardAssignment.loadWeights(weightsFile)

        fun verifyProfile(label: String, excludeMigrationTests: Boolean, weightsInput: Map<String, Double>) {
            // build.gradle.kts の Test.exclude 側と同じロジック: 実際に走るクラス集合に
            // 限定して貪欲法を適用する。
            val effectiveWeights = if (excludeMigrationTests) {
                weightsInput.filterKeys { !ShardAssignment.isMigrationReplayClass(it) }
            } else {
                weightsInput
            }
            val weightedBucketOf = ShardAssignment.buildGreedyAssignment(effectiveWeights, total)

            val shardCounts = IntArray(total)
            val shardSeconds = DoubleArray(total)
            val zeroButShouldBeOne = mutableListOf<String>()
            val multiAssigned = mutableListOf<Pair<String, Int>>()
            val migrationLeaked = mutableListOf<String>()

            classFiles.forEach { cf ->
                val migrationExcluded = excludeMigrationTests && ShardAssignment.isMigrationReplayClass(cf.topLevelFqcn)
                // トップレベルクラスの .class ファイルか（ネストクラスのファイル名は必ず '$' を含む）。
                // 集計（shardCounts / shardSeconds）はレポート表示用の数値であり、同一トップレベル
                // クラスに属する複数 .class ファイル分の重複加算を避けるため、代表ファイル
                // （トップレベル自身の .class）1件のみをカウントする。
                // 取りこぼし・重複割当・migration漏れの判定（このあとの if/when）は
                // 全 .class ファイルに対して行う（ネストクラス単位の正規化バグも検出するため）。
                val isTopLevelFile = !cf.path.substringAfterLast('/').removeSuffix(".class").contains('$')
                var includedCount = 0
                for (shardIndex in 0 until total) {
                    // 実運用と全く同じ2つの述語をそのまま呼ぶ（OR で除外＝どちらかが true なら除外）。
                    val shardExcluded =
                        ShardAssignment.shouldExcludeForShard(cf.path, false, shardIndex, total, weightedBucketOf)
                    val migrationFilterExcluded =
                        excludeMigrationTests && ShardAssignment.shouldExcludeMigrationReplay(cf.path, false)
                    val executedHere = !shardExcluded && !migrationFilterExcluded
                    if (executedHere) {
                        includedCount++
                        if (isTopLevelFile) {
                            shardCounts[shardIndex]++
                            shardSeconds[shardIndex] += (effectiveWeights[cf.topLevelFqcn] ?: 0.0)
                        }
                    }
                }
                if (migrationExcluded) {
                    if (includedCount != 0) migrationLeaked.add(cf.path)
                } else {
                    when {
                        includedCount == 0 -> zeroButShouldBeOne.add(cf.path)
                        includedCount >= 2 -> multiAssigned.add(cf.path to includedCount)
                    }
                }
            }

            // ネストクラスの sibling grouping 検証。
            // 【重要】グルーピングキーは ShardAssignment.fqcnTopLevelFromClassPath() を
            // 一切呼ばない、完全に独立した文字列操作（substringBefore('$')）で作る。
            // cf.topLevelFqcn（＝ ShardAssignment 経由で算出済みの値）を使ってグルーピング
            // すると、まさに検証対象の正規化関数が壊れた場合に「壊れた基準で壊れた結果を
            // 自己採点する」自己欺瞞になり、検出できなくなる（実際に substringBefore('$') を
            // 意図的に外す破壊テストで、topLevelFqcn によるグルーピングでは検出できないことを
            // 確認した）。生パスから独立に切り出した groupKey を使うことで、この種の
            // 正規化破損を確実に検出する。
            val siblingMismatch = mutableListOf<String>()
            classFiles.groupBy { it.path.substringBefore('$') }.forEach { (groupKey, files) ->
                if (files.size <= 1) return@forEach
                // migration グループの判定も ShardAssignment を経由しない独立実装で行う
                // （グルーピング自体の独立性を保つため。実際の除外可否判定は
                // ShardAssignment.shouldExcludeMigrationReplay() が別途行っている）。
                val independentFqcn = groupKey.removeSuffix(".class").replace('/', '.')
                val independentSimpleName = independentFqcn.substringAfterLast('.')
                val isMigrationGroup = excludeMigrationTests &&
                    independentFqcn.contains(".migration.") && independentSimpleName.contains("Flyway")
                if (isMigrationGroup) return@forEach
                val shardsUsed = files.mapNotNull { cf ->
                    (0 until total).firstOrNull { shardIndex ->
                        !ShardAssignment.shouldExcludeForShard(cf.path, false, shardIndex, total, weightedBucketOf)
                    }
                }.toSet()
                if (shardsUsed.size > 1) siblingMismatch.add(groupKey)
            }

            logger.lifecycle(
                "[verifyShardCoverage] [$label] shard別実行クラス数: " +
                    shardCounts.mapIndexed { i, c -> "$i=$c" }.joinToString(", ") +
                    " / shard別合計秒(重み既知分のみ): " +
                    shardSeconds.mapIndexed { i, t -> "$i=${"%.1f".format(t)}" }.joinToString(", ")
            )

            if (zeroButShouldBeOne.isNotEmpty()) {
                logger.error(
                    "[verifyShardCoverage] [$label] どの shard でも実行されないクラスが " +
                        "${zeroButShouldBeOne.size} 件ある（取りこぼし）: " +
                        zeroButShouldBeOne.take(20).joinToString(", ")
                )
            }
            if (multiAssigned.isNotEmpty()) {
                logger.error(
                    "[verifyShardCoverage] [$label] 2 つ以上の shard で実行されるクラスが " +
                        "${multiAssigned.size} 件ある（重複割当）: " +
                        multiAssigned.take(20).joinToString(", ") { (p, n) -> "$p(${n}件)" }
                )
            }
            if (migrationLeaked.isNotEmpty()) {
                logger.error(
                    "[verifyShardCoverage] [$label] migration除外プロファイルなのに実行対象へ漏れているクラスが " +
                        "${migrationLeaked.size} 件ある: " + migrationLeaked.take(20).joinToString(", ")
                )
            }
            if (siblingMismatch.isNotEmpty()) {
                logger.error(
                    "[verifyShardCoverage] [$label] ネストクラスが異なる shard に分断されているトップレベル" +
                        "クラスが ${siblingMismatch.size} 件ある: " + siblingMismatch.take(20).joinToString(", ")
                )
            }

            require(
                zeroButShouldBeOne.isEmpty() && multiAssigned.isEmpty() &&
                    migrationLeaked.isEmpty() && siblingMismatch.isEmpty()
            ) {
                "[$label] 取りこぼし${zeroButShouldBeOne.size}件・重複${multiAssigned.size}件・" +
                    "migration漏れ${migrationLeaked.size}件・ネスト分断${siblingMismatch.size}件を検出した" +
                    "（詳細は上の [verifyShardCoverage] ログを参照）"
            }
        }

        verifyProfile("重み表なし・migration除外なし（フル実行プロファイル）", false, emptyMap())
        verifyProfile("重み表なし・migration除外あり（通常PRプロファイル）", true, emptyMap())
        verifyProfile("重み表あり・migration除外なし（nightly-fullプロファイル）", false, rawWeights)
        verifyProfile("重み表あり・migration除外あり（通常PRプロファイル）", true, rawWeights)

        logger.lifecycle("[verifyShardCoverage] OK: 4プロファイル全てで取りこぼし・重複・migration漏れ・ネスト分断なしを確認した")
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
    // ddl-auto:create-drop + 全 Entity 構築で 5 分前後かかり、さらに初回 /v3/api-docs
    // スキャンが ~1 分かかるため、300 秒では起動完了直後にタイムアウトする
    // （worktree のコールド環境で実測: 起動完了 ~300s + 初回スキャン ~57s）。
    // 余裕を持って 1800 秒に設定する（CI 運用は廃止しローカル生成のみのため、長めの待機で問題ない。
    // 2026-08-20 実測: 並行セッションでgradleデーモン6個ビジーの高負荷時、600秒では起動が間に合わずタイムアウトした）。
    waitTimeInSeconds.set(1800)
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
