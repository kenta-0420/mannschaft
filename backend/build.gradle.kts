plugins {
    id("org.springframework.boot") version "3.5.13"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
    // OWASP Dependency-Check: 依存ライブラリの既知 CVE をスキャンする（週次 CI で使用）
    id("org.owasp.dependencycheck") version "12.1.3"
    id("mannschaft.backend-conventions")
}

group = "com.mannschaft"
version = "0.0.1-SNAPSHOT"
description = "Universal Organization Management Platform"

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
// 生成用フォークプロセスの待ち受けポート。
// 既定は 8082（dev サーバー :8080 と競合しない）だが、-PopenApiPort=8099 で上書きできる。
// WSL2 の mirrored networking では WSL 側の listener も Windows の localhost に現れるため、
// 並行セッションの E2E バックエンドが :8082 を掴んでいると、
// フォークが bind できない／プラグインの GET が他セッションのアプリに繋がって別物の spec を拾う。
// 衝突時は別ポートを指定して回避する。
val openApiGenPort: String = (project.findProperty("openApiPort") as String?) ?: "8082"

openApi {
    apiDocsUrl.set("http://localhost:$openApiGenPort/v3/api-docs")
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
        jvmArgs.add("-Dserver.port=$openApiGenPort")
        // bootRun は開発者向けの高速起動最適化として -XX:TieredStopAtLevel=1（C1 のみ）を付ける。
        // これは「起動してすぐ手で触る」用途には有効だが、generateOpenApiDocs の起動は
        // 全 Entity の DDL 生成・全 Bean 初期化・全 Controller スキャンという重い CPU バウンドの処理で、
        // C1 のみだと数十分規模まで伸びて waitTimeInSeconds を食い潰す。
        // 後勝ちで階層コンパイルを全段有効に戻す。
        jvmArgs.add("-XX:TieredStopAtLevel=4")
        // MapProperty.put() で systemProperties にも設定し二重に適用する
        systemProperties.put("spring.profiles.active", "openapi-gen")
        systemProperties.put("server.port", openApiGenPort)
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
