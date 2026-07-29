package com.mannschaft.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>DB 格納時刻の基準タイムゾーン（＝ UTC）を固定する番人テスト</b>（Issue #2486）。
 *
 * <h2>背景 — 「格納基準が環境ごとに割れていた」</h2>
 * <p>本アプリの JVM 既定 TZ は {@link TimeZoneConfig} が {@code Asia/Tokyo} に強制し、
 * エンティティの {@code @PrePersist} は素の {@code LocalDateTime.now()}（＝ JST 壁時計）で
 * 時刻を採る。この {@code LocalDateTime} が <b>DB にどの壁時計として書かれるか</b>を決めるのが
 * {@code spring.jpa.properties.hibernate.jdbc.time_zone} である。</p>
 *
 * <p>Hibernate はこの設定があるとき {@code PreparedStatement.setTimestamp(i, ts, Calendar.getInstance(UTC))}
 * を使う。{@code LocalDateTime}（JST 壁時計）は一旦 JVM 既定 TZ で瞬間に解決され、その瞬間が UTC 壁時計
 * として DB に書かれる。つまり<b>アプリ側の値は JST 壁時計のまま・DB の格納値だけが UTC 壁時計</b>になり、
 * Hibernate 経由の往復は無損失である。設定が無い場合は接続既定（＝ JST）で書かれ、格納値が JST 壁時計になる。</p>
 *
 * <p>是正前、この指定は <b>{@code backend/src/test/resources/application-test.yml} にしか存在せず</b>、
 * 共有の {@code application.yml} には無かった。結果として:</p>
 * <ul>
 *   <li><b>test</b>（Testcontainers）— 格納基準 <b>UTC</b></li>
 *   <li><b>local / ci</b> — 格納基準 <b>JST</b>（docker-compose の {@code --default-time-zone=+09:00}、
 *       {@code application-ci.yml} の {@code serverTimezone=Asia/Tokyo}）</li>
 *   <li><b>prod 想定</b>（{@code infra/terraform}）— JDBC {@code serverTimezone=UTC} ＋ RDS {@code time_zone=UTC}</li>
 * </ul>
 * <p>という<b>三者三様</b>の状態だった。同じコードが環境ごとに違う壁時計を書く以上、DB を直接読む生 SQL
 * （{@code CONVERT_TZ} / {@code DATE()} / 手動 INSERT のフィクスチャ）は必ずどこかの環境で 9 時間ずれる。</p>
 *
 * <h2>なぜ「今」UTC に倒したか</h2>
 * <p>本番環境はまだ存在せず（インフラ初回構築が未マージ）、<b>再解釈が必要な既存データがゼロ</b>である。
 * 格納基準の変更は本来「全既存行の意味が変わる」極めて侵襲的な操作であり、データが入ってからでは
 * 変換マイグレーションなしには実施できない。データが無い今こそが唯一の無コストな実施タイミングだった。</p>
 *
 * <p>UTC を選ぶ理由は、prod 想定（RDS {@code time_zone=UTC}）と test が既に UTC であり、
 * <b>多数派かつ将来の正</b>だったこと、そして {@code mannschaft.audit.stored-zone-offset} の既定
 * {@code "+00:00"}（{@code CONVERT_TZ} の変換元）が UTC 格納を前提に置かれていたことによる。
 * JST に倒すとこれら全てを逆向きに直す必要があり、かつ将来の多リージョン展開で必ず破綻する。</p>
 *
 * <h2>この番人が守る 4 つの不変条件</h2>
 * <ul>
 *   <li><b>AC-1</b>: 共有 {@code application.yml} に {@code hibernate.jdbc.time_zone: UTC} が存在する。
 *       プロファイル個別ではなく<b>共有側</b>に置くことが本質で、そうしないと新プロファイル追加時に
 *       再び取りこぼされる（今回の事故そのもの）。</li>
 *   <li><b>AC-2</b>: {@code main/resources} の全 {@code application*.yml} の JDBC URL に現れる
 *       {@code serverTimezone} は必ず {@code UTC}。この値は「MySQL サーバがどの TZ か」をドライバへ
 *       教えるものなので、実サーバの {@code time_zone} と食い違うと {@code Timestamp} 変換が静かに壊れる。</li>
 *   <li><b>AC-3</b>: ローカル {@code docker-compose.yml} の MySQL {@code --default-time-zone} が UTC 相当。
 *       ここだけ JST に残すと、ローカルだけ生 SQL の結果が 9 時間ずれて「手元では再現しない」不具合を生む。</li>
 *   <li><b>AC-4</b>: {@code mannschaft.audit.stored-zone-offset} の既定が {@code "+00:00"}。
 *       {@code AuditLogRepository} の {@code CONVERT_TZ(created_at, :storedZoneOffset, :tzOffset)} の
 *       <b>変換元</b>であり、格納基準が UTC であることと表裏一体。片方だけ動かすと活動日数集計が日単位でずれる。</li>
 * </ul>
 *
 * <h2>実測に基づく注記 — {@code application-test.yml} は 2 つ存在し、test 側が完全に勝つ</h2>
 * <p>{@code src/main/resources} と {@code src/test/resources} の両方に {@code application-test.yml} がある。
 * Gradle の test ランタイムクラスパスは {@code build/resources/test} が {@code build/resources/main} より
 * 前に来るため、Spring Boot の {@code classpath:} 解決（先頭 1 件のみ採用）で
 * <b>{@code src/test/resources} 側が main 側を完全に隠蔽する</b>ことを実測で確認済み
 * （{@code spring.datasource.url} が {@code null}＝main 側の Testcontainers URL が読まれていない、
 * {@code ddl-auto} が {@code create}＝test 側の値、で確定）。したがって main 側の
 * {@code application-test.yml} は gradle test 実行では<b>死んでいる</b>。
 * ただし {@code bootRun --spring.profiles.active=test} のようにテストリソースがクラスパスに乗らない
 * 起動経路では main 側が生きるため、削除せず値だけ UTC へ揃えてある。</p>
 *
 * <h2>スコープ外（本テストは検査しない）</h2>
 * <p>349 ファイルの {@code @PrePersist} が素の {@code LocalDateTime.now()} を使い、注入済み {@code Clock}
 * （{@link ClockConfig} の {@code Clock.system(ZoneOffset.UTC)}）を経由していない件は本番の格納基準とは
 * 独立した別課題であり、ここでは扱わない。</p>
 */
@DisplayName("DB格納時刻の基準TZ=UTC 番人（Issue #2486）")
class TimeZoneStorageBasisGuardTest {

    /** {@code backend/src/main/resources}（テストの CWD は {@code backend/}）。 */
    private static final Path MAIN_RESOURCES = Paths.get("src", "main", "resources");

    /** リポジトリルートの {@code docker-compose.yml}（{@code backend/} の 1 つ上）。 */
    private static final Path DOCKER_COMPOSE = Paths.get("..", "docker-compose.yml");

    /** 共有設定（全プロファイルの土台）。 */
    private static final Path SHARED_APPLICATION_YML = MAIN_RESOURCES.resolve("application.yml");

    /** JDBC URL 中の {@code serverTimezone=<値>}。値は {@code &} / 引用符 / 空白の手前まで。 */
    private static final Pattern SERVER_TIMEZONE =
            Pattern.compile("serverTimezone=([^&\"'\\s]+)");

    /** docker-compose の {@code --default-time-zone=<値>}。 */
    private static final Pattern DEFAULT_TIME_ZONE =
            Pattern.compile("--default-time-zone=(\\S+)");

    /** {@code ${ENV_NAME:デフォルト値}} からデフォルト値を取り出す。 */
    private static final Pattern PLACEHOLDER_DEFAULT =
            Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    /** UTC と等価とみなす {@code --default-time-zone} の表記。 */
    private static final List<String> UTC_EQUIVALENT_OFFSETS = List.of("+00:00", "UTC", "+0:00");

    // ============================================================
    // AC-1
    // ============================================================

    @Test
    @DisplayName("AC-1: 共有 application.yml が hibernate.jdbc.time_zone=UTC を宣言している")
    void 共有applicationYmlがUTC格納基準を宣言している() {
        Properties props = loadYaml(SHARED_APPLICATION_YML);

        Object value = props.get("spring.jpa.properties.hibernate.jdbc.time_zone");

        assertThat(value)
                .as("""
                    %s に spring.jpa.properties.hibernate.jdbc.time_zone が無い。
                    この指定が共有側に無いと、指定を持つプロファイル（test）だけが UTC 格納になり、
                    local / ci / prod は JVM 既定 TZ（Asia/Tokyo）で格納されて基準が割れる。
                    プロファイル個別ではなく共有 application.yml に置くこと（Issue #2486）。""",
                        SHARED_APPLICATION_YML)
                .isNotNull();

        assertThat(String.valueOf(value).trim())
                .as("DB 格納時刻の基準 TZ は UTC で固定する（Issue #2486）")
                .isEqualTo("UTC");
    }

    // ============================================================
    // AC-2
    // ============================================================

    @Test
    @DisplayName("AC-2: main/resources の全 application*.yml で serverTimezone は UTC のみ")
    void 全プロファイルのJDBC_URLのserverTimezoneがUTCである() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path yml : listProfileConfigFiles()) {
            String content = Files.readString(yml, StandardCharsets.UTF_8);
            Matcher m = SERVER_TIMEZONE.matcher(content);
            while (m.find()) {
                String tz = m.group(1);
                if (!"UTC".equals(tz)) {
                    violations.add(yml + " → serverTimezone=" + tz);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("""
                 JDBC URL の serverTimezone が UTC 以外になっている:
                 %s

                 serverTimezone は「接続先 MySQL サーバがどの TZ か」をドライバへ伝える値であり、
                 実サーバの time_zone と食い違うと java.sql.Timestamp の変換が静かにずれる。
                 DB 格納基準を UTC に統一した以上（Issue #2486）、この値も UTC でなければならない。"""
                    .formatted(String.join("\n", violations)));
        }
    }

    // ============================================================
    // AC-3
    // ============================================================

    @Test
    @DisplayName("AC-3: docker-compose.yml の MySQL default-time-zone が UTC 相当")
    void ローカルMySQLの既定タイムゾーンがUTC相当である() throws IOException {
        assertThat(Files.exists(DOCKER_COMPOSE))
                .as("docker-compose.yml が %s に見つからない（テストの CWD は backend/ を想定）",
                        DOCKER_COMPOSE.toAbsolutePath().normalize())
                .isTrue();

        String content = Files.readString(DOCKER_COMPOSE, StandardCharsets.UTF_8);
        Matcher m = DEFAULT_TIME_ZONE.matcher(content);

        assertThat(m.find())
                .as("docker-compose.yml の MySQL に --default-time-zone の指定が無い。"
                        + "未指定だとホスト依存（SYSTEM）になり、格納基準が開発者ごとに割れる。")
                .isTrue();

        String tz = m.group(1);
        assertThat(tz)
                .as("""
                    ローカル MySQL の既定 TZ が UTC 相当でない（実際の値: %s）。
                    ここが JST のままだと、生 SQL（CONVERT_TZ / DATE() / NOW()）の結果が
                    ローカルだけ 9 時間ずれ、「手元では再現しない」不具合を生む（Issue #2486）。""", tz)
                .isIn(UTC_EQUIVALENT_OFFSETS);
    }

    // ============================================================
    // AC-4
    // ============================================================

    @Test
    @DisplayName("AC-4: mannschaft.audit.stored-zone-offset の既定が +00:00 のまま")
    void 監査ログ格納基準オフセットの既定がUTCのままである() {
        Properties props = loadYaml(SHARED_APPLICATION_YML);

        Object raw = props.get("mannschaft.audit.stored-zone-offset");
        assertThat(raw)
                .as("%s に mannschaft.audit.stored-zone-offset が無い", SHARED_APPLICATION_YML)
                .isNotNull();

        String defaultValue = extractPlaceholderDefault(String.valueOf(raw).trim());

        assertThat(defaultValue)
                .as("""
                    mannschaft.audit.stored-zone-offset の既定が +00:00 でない（実際: %s）。
                    この値は AuditLogRepository の CONVERT_TZ(created_at, :storedZoneOffset, :tzOffset) の
                    「変換元」であり、audit_logs.created_at が UTC 壁時計で格納されていることと表裏一体。
                    格納基準（AC-1）とこの既定は必ず同時に UTC でなければ、activeDays 集計が日単位でずれる。""",
                        defaultValue)
                .isEqualTo("+00:00");
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /**
     * 検査対象の設定ファイル一覧。{@code main/resources} 直下の {@code application*.yml} に加え、
     * {@code application-local.yml.example}（各開発者が {@code application-local.yml} として複製する雛形）も
     * 含める。ローカル雛形の {@code serverTimezone} が docker-compose（AC-3）と食い違うと、
     * 開発環境だけが再び基準割れを起こすため。
     */
    private static List<Path> listProfileConfigFiles() throws IOException {
        try (Stream<Path> files = Files.list(MAIN_RESOURCES)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("application")
                                && (name.endsWith(".yml") || name.endsWith(".yml.example"));
                    })
                    .sorted()
                    .toList();
        }
    }

    /** YAML をフラットな {@code a.b.c=値} 形式へ展開して読む（入れ子・複数ドキュメントに対応）。 */
    private static Properties loadYaml(Path path) {
        assertThat(Files.exists(path)).as("%s が存在しない", path.toAbsolutePath().normalize()).isTrue();
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(path.toFile()));
        factory.afterPropertiesSet();
        Properties props = factory.getObject();
        assertThat(props).as("%s の YAML 解析に失敗した", path).isNotNull();
        return props;
    }

    /**
     * {@code ${ENV:デフォルト}} 形式ならデフォルト部分を、そうでなければ値そのものを返す。
     * 環境変数による上書きは運用上の自由度として許すが、<b>既定値</b>は UTC で固定する。
     */
    private static String extractPlaceholderDefault(String rawValue) {
        Matcher m = PLACEHOLDER_DEFAULT.matcher(rawValue);
        return m.matches() ? m.group(1) : rawValue;
    }
}
