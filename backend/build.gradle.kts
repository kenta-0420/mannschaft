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
// 【なぜ object に一本化するか】
//   以前は「実際にテストを除外するロジック」と「検証タスク（verifyShardCoverage）の
//   割当計算ロジック」が別実装で存在し、検証タスクは自前で割当を再計算してから
//   1クラス=1回ループで加算するだけだったため、sum==総数 が構造的に自明で
//   （＝フィルタが反転・全除外・多重割当に壊れても検証は必ず合格する）偽 green
//   だった。本 object の isIncluded() を「テスト実行時の exclude 判定」と
//   「検証タスクの割当集計」の両方が直接呼ぶことで、フィルタ本体が壊れれば
//   検証も同じ壊れ方の影響を受けて確実に fail する構造にする。
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
     * （＝呼び出し側は isIncluded() で自動的に安定ハッシュへフォールバックする）。
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
    fun bucketOf(fqcnTopLevel: String, shardTotal: Int, weightedBucketOf: Map<String, Int>): Int =
        weightedBucketOf[fqcnTopLevel] ?: hashBucket(fqcnTopLevel, shardTotal)

    /**
     * 【単一の判定関数】このクラスが指定した shardIndex に含まれる（実行される）か。
     * build.gradle.kts の Test.exclude { } と verifyShardCoverage タスクの両方が
     * 必ずこの関数を通して判定する（二重実装を禁止し、検証の自己欺瞞を防ぐ）。
     */
    fun isIncluded(fqcnTopLevel: String, shardIndex: Int, shardTotal: Int, weightedBucketOf: Map<String, Int>): Boolean =
        bucketOf(fqcnTopLevel, shardTotal, weightedBucketOf) == shardIndex

    /** .class ファイルの相対 path（"/" 区切り）からトップレベル FQCN を復元する。 */
    fun fqcnTopLevelFromClassPath(path: String): String =
        path.removeSuffix(".class").replace('/', '.').substringBefore('$')
}

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
    // ローカル（WSL2 Docker）環境では -Pfork.every=0 で無効化し、1コンテナ共有で高速化できる。
    // perfTask は単一クラスの重量級 IT ゆえ forkEvery=0（1 JVM 共有）で無駄な再 fork を避ける。
    setForkEvery(if (isPerfTask) 0L else ((project.findProperty("fork.every") as String?)?.toLong() ?: 500L))
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
    //     '$' より前のトップレベル名でハッシュ／重み参照する（取りこぼし・分断を防ぐ）。
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
        val weights = ShardAssignment.loadWeights(weightsFile)
        val weightedBucketOf = ShardAssignment.buildGreedyAssignment(weights, shardTotal)
        if (weights.isNotEmpty()) {
            val shardTotals = DoubleArray(shardTotal)
            weights.forEach { (fqcn, seconds) -> shardTotals[weightedBucketOf.getValue(fqcn)] += seconds }
            logger.lifecycle(
                "[shard] 重み表 ${weightsFile.name} を読み込み ${weights.size} クラスを貪欲法で割り付けた。" +
                    "shard 別合計(秒): " + shardTotals.mapIndexed { i, t -> "$i=${"%.1f".format(t)}" }.joinToString(", ")
            )
        } else {
            logger.lifecycle("[shard] 重み表が見つからないため全クラスを安定ハッシュへフォールバックする")
        }

        logger.lifecycle("[shard] テストを $shardTotal 分割し index=$shardIndex のみ実行する")
        exclude { element ->
            // ディレクトリは除外判定対象外（false=含める）
            if (element.isDirectory) return@exclude false
            val path = element.path
            if (!path.endsWith(".class")) return@exclude false
            val fqcnTopLevel = ShardAssignment.fqcnTopLevelFromClassPath(path)
            // 自分の shard に含まれないクラスを除外する（true=除外）。
            // ShardAssignment.isIncluded は verifyShardCoverage タスクと共通の単一判定関数。
            !ShardAssignment.isIncluded(fqcnTopLevel, shardIndex, shardTotal, weightedBucketOf)
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
    //   の AND を取る。防御的に AND とする理由:
    //   - パッケージのみだと MigrationPrimaryKeyConventionTest（規約 guard・コンテナ不要）
    //     や StoragePathMigrationBatchServiceTest（Mockito 単体テスト）まで巻き込む。
    //   - クラス名 Flyway のみだと FlywayTimestampNamingGuardTest（採番 guard・高速）や
    //     SharedFileLinkFlywayColumnIT / ProxyInputConsentS3KeyFlywaySchemaTest
    //     （こちらは @SpringBootTest を使う＝性質が異なる）まで巻き込む。
    //   AND により対象は 65 クラス。全件が MySQLContainer を自前起動し、
    //   @SpringBootTest を使わない純 Flyway + 生 JDBC のテストであることを確認済み。
    // =====================================================================
    val excludeMigrationTests =
        (project.findProperty("excludeMigrationTests") as String?)?.toBoolean() ?: false
    if (excludeMigrationTests) {
        logger.lifecycle(
            "[migration] Flyway マイグレーション再生テストを除外する" +
                "（db/migration/** 無変更のため。フル実行は backend-nightly-full.yml が担保）"
        )
        exclude { element ->
            if (element.isDirectory) return@exclude false
            val path = element.path
            if (!path.endsWith(".class")) return@exclude false
            val simpleName = path
                .removeSuffix(".class")
                .substringAfterLast('/')
                .substringBefore('$')
            // migration パッケージ配下 かつ 単純クラス名に Flyway を含む → 除外
            path.contains("/migration/") && simpleName.contains("Flyway")
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
// 【検証方法（重要）】ここで割当を独自に再計算するのではなく、実際に Test.exclude
// フィルタが使うのと同じ ShardAssignment.isIncluded() を、shardIndex = 0..total-1
// の「全て」に対してコンパイル済みテストクラス全件へ適用する。1クラスにつき
// isIncluded() が true を返す shardIndex の個数を数え、その個数がちょうど 1 で
// あることを assert する（0件=取りこぼし、2件以上=重複割当として fail）。
// フィルタ本体（isIncluded / bucketOf / buildGreedyAssignment）が反転・全除外・
// 多重割当のいずれに壊れても、この検証は同じ壊れ方の影響を受けるため必ず fail する
// （＝本体と別実装で再計算する旧方式が持っていた自明合格の構造的欠陥を排除）。
//
// 使い方: cd backend && ./gradlew verifyShardCoverage -Pshard.total=6
// （testClasses への依存によりコンパイルは自動で行われる）
// =============================================================================
tasks.register("verifyShardCoverage") {
    group = "verification"
    description = "実際の Test.exclude フィルタ（ShardAssignment.isIncluded）を全クラス×全shardIndexへ適用し、" +
        "各クラスがちょうど1つのshardでincludedになることを検証する"
    dependsOn(tasks.named("testClasses"))
    doLast {
        val total = (project.findProperty("shard.total") as String?)?.toIntOrNull() ?: 6
        require(total > 1) { "shard.total は 2 以上を指定すること（現在: $total）" }

        // コンパイル済みテストクラス全件からトップレベル FQCN の集合を得る
        val classesDirs = sourceSets["test"].output.classesDirs.files
        val fqcns = LinkedHashSet<String>()
        classesDirs.forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { f ->
                    val rel = f.relativeTo(dir).path.replace(File.separatorChar, '.')
                    val fqcnTopLevel = rel.removeSuffix(".class").substringBefore('$')
                    fqcns.add(fqcnTopLevel)
                }
        }
        val classTotal = fqcns.size
        logger.lifecycle("[verifyShardCoverage] コンパイル済みテストクラス総数（トップレベル）: $classTotal")

        fun verify(label: String, weights: Map<String, Double>) {
            // 実際の Test.exclude と全く同じ経路（ShardAssignment）で weightedBucketOf を構築する。
            val weightedBucketOf = ShardAssignment.buildGreedyAssignment(weights, total)

            val counts = IntArray(total)
            val zeroAssigned = mutableListOf<String>()
            val multiAssigned = mutableListOf<Pair<String, Int>>()

            fqcns.forEach { fqcn ->
                // 実運用の Test.exclude が呼ぶのと同一の isIncluded() を、
                // shardIndex = 0..total-1 の全パターンに実際に適用する。
                var includedCount = 0
                for (shardIndex in 0 until total) {
                    if (ShardAssignment.isIncluded(fqcn, shardIndex, total, weightedBucketOf)) {
                        counts[shardIndex]++
                        includedCount++
                    }
                }
                when {
                    includedCount == 0 -> zeroAssigned.add(fqcn)
                    includedCount >= 2 -> multiAssigned.add(fqcn to includedCount)
                }
            }

            val sum = counts.sum()
            logger.lifecycle(
                "[verifyShardCoverage] [$label] shard別 included 数: " +
                    counts.mapIndexed { i, c -> "$i=$c" }.joinToString(", ") +
                    " / 合計=$sum / クラス総数=$classTotal"
            )

            if (zeroAssigned.isNotEmpty()) {
                logger.error(
                    "[verifyShardCoverage] [$label] どの shard にも included にならないクラスが " +
                        "${zeroAssigned.size} 件ある（取りこぼし）: " +
                        zeroAssigned.take(20).joinToString(", ") +
                        if (zeroAssigned.size > 20) " ...(以下省略)" else ""
                )
            }
            if (multiAssigned.isNotEmpty()) {
                logger.error(
                    "[verifyShardCoverage] [$label] 2 つ以上の shard で included になるクラスが " +
                        "${multiAssigned.size} 件ある（重複割当）: " +
                        multiAssigned.take(20).joinToString(", ") { (fqcn, n) -> "$fqcn(${n}件)" } +
                        if (multiAssigned.size > 20) " ...(以下省略)" else ""
                )
            }

            require(zeroAssigned.isEmpty() && multiAssigned.isEmpty()) {
                "[$label] 取りこぼし ${zeroAssigned.size} 件・重複割当 ${multiAssigned.size} 件を検出した" +
                    "（詳細は上の [verifyShardCoverage] ログを参照）"
            }
            require(sum == classTotal) {
                "[$label] included 合計($sum) がクラス総数($classTotal) と一致しない"
            }
        }

        // (a) 重み表なし＝全クラスが安定ハッシュにフォールバックするケース
        verify("重み表なし（ハッシュのみ）", emptyMap())

        // (b) 重み表ありのケース（実ファイルを読み込む。存在しなければ空 map として同じ検証を再実行）
        val weightsFile = file("src/test/resources/shard-weights.properties")
        val weights = ShardAssignment.loadWeights(weightsFile)
        verify("重み表あり（貪欲法＋新規クラスはハッシュへフォールバック）", weights)

        logger.lifecycle("[verifyShardCoverage] OK: 重み表あり／なし双方で取りこぼし・重複なしを確認した")
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
