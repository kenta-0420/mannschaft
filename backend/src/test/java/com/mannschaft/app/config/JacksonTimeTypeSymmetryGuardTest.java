package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>java.time 型の JSON 往復対称性を固定する番人テスト</b>（Issue #2508 / AC-9）。
 *
 * <h2>背景 — 「出力だけカスタムして入力を忘れる」欠陥</h2>
 *
 * <p>{@link JacksonConfig} は {@code @Primary ObjectMapper} に
 * {@code LocalDateTimeTimezoneSerializer} を登録して {@link LocalDateTime} の<b>出力</b>を
 * ユーザー TZ へ変換していたが、<b>対になるデシリアライザが無かった</b>。結果として:</p>
 *
 * <ul>
 *   <li>送信 — 保持値を JST 壁時計と決め打ちし、ユーザー TZ へ変換してオフセット付きで出力</li>
 *   <li>受信 — Jackson 標準（{@code ISO_LOCAL_DATE_TIME}）のまま。<b>ユーザー TZ を完全に無視</b>し、
 *       しかも<b>自分が出力したオフセット付き文字列を読み戻せない</b></li>
 * </ul>
 *
 * <p>ズレ幅は（ユーザー TZ のオフセット）−（+09:00）で、日本のユーザーだけ偶然 0 になるため
 * 発覚が遅れた。同型の欠陥は {@code payload_json} の読み戻し（バッチ経路）でも実害を出していた
 * （予約出欠の {@code attendanceDeadline} = PR #2523、予約アンケートの {@code startsAt}/{@code expiresAt}）。</p>
 *
 * <h2>この番人が守る不変条件</h2>
 * <ul>
 *   <li><b>AC-9-1</b>: {@code @Primary ObjectMapper} が<b>出力をカスタムしている java.time 型</b>は、
 *       <b>自分の出力を読み戻して元の値に戻れる</b>こと。将来 java.time の別の型で
 *       シリアライザだけを足した者は、ここで機械的に止められる。</li>
 *   <li><b>AC-9-2</b>: 出力をカスタムした型には検体（サンプル値）が登録されていること。
 *       検体の無い型を黙って検査対象外にしない（＝番人の空洞化を防ぐ）。</li>
 *   <li><b>AC-9-3</b>: {@code LocalDateTimeTimezoneDeserializer.SERVER_ZONE} と、対になる
 *       {@code LocalDateTimeTimezoneSerializer} が見る {@code ZoneId.systemDefault()} が一致すること。
 *       片方が定数・片方が JVM 既定という<b>非対称な参照</b>になっているため、
 *       {@link TimeZoneConfig} が動くとシリアライザだけ追従して往復が静かに壊れる。</li>
 * </ul>
 *
 * <h2>なぜ「登録の有無」ではなく「往復できること」を検査するのか</h2>
 *
 * <p>「シリアライザがあるならデシリアライザもある」という登録の対称性は、
 * Jackson の {@code SimpleModule} 内部（{@code _serializers} / {@code _deserializers} の private フィールド）を
 * リフレクションで覗かないと直接は読めず、Jackson のバージョン更新で壊れやすい。</p>
 *
 * <p>一方<b>本当に守りたいのは「往復して値が変わらないこと」</b>であり、これは
 * 公開 API（{@link SerializerProvider#findValueSerializer(Class)} と
 * {@code writeValueAsString} / {@code readValue}）だけで検査できる。
 * デシリアライザの登録漏れは「自分の出力を読めない（例外）」または「読めるが値が変わる」として必ず現れるため、
 * <b>登録の対称性より広く、かつ実害に直結する不変条件</b>を固定していることになる。</p>
 *
 * <h2>意図的に片方だけにしたい型の扱い</h2>
 * <p>{@link #ASYMMETRY_ALLOWED} に<b>理由付き</b>で登録する。黙って通す作りにはしていない
 * （許可リストに無い型が往復に失敗したら fail する）。</p>
 */
@DisplayName("java.time 型の JSON 往復対称性 番人（Issue #2508 AC-9）")
class JacksonTimeTypeSymmetryGuardTest {

    /**
     * 検査対象の java.time 型と検体。
     *
     * <p>「{@code @Primary ObjectMapper} が出力をカスタムしている型」を機械的に判定したうえで、
     * その型の往復を検体で検証する。ここに列挙されていない型が将来カスタムされたら
     * <b>AC-9-2 が検体不足で fail する</b>ので、検査対象から静かに漏れることはない。</p>
     */
    private static final Map<Class<?>, Object> SAMPLES = buildSamples();

    private static Map<Class<?>, Object> buildSamples() {
        Map<Class<?>, Object> samples = new LinkedHashMap<>();
        // 基準の瞬間 2026-05-22T00:15:20Z（= JST 2026-05-22T09:15:20）
        samples.put(LocalDateTime.class, LocalDateTime.of(2026, 5, 22, 9, 15, 20));
        samples.put(LocalDate.class, LocalDate.of(2026, 5, 22));
        samples.put(LocalTime.class, LocalTime.of(9, 15, 20));
        samples.put(OffsetDateTime.class,
                OffsetDateTime.of(2026, 5, 22, 9, 15, 20, 0, ZoneOffset.ofHours(9)));
        samples.put(OffsetTime.class, OffsetTime.of(9, 15, 20, 0, ZoneOffset.ofHours(9)));
        samples.put(ZonedDateTime.class,
                ZonedDateTime.of(2026, 5, 22, 9, 15, 20, 0, ZoneOffset.ofHours(9)));
        samples.put(Instant.class, Instant.parse("2026-05-22T00:15:20Z"));
        samples.put(Duration.class, Duration.ofMinutes(90));
        samples.put(Period.class, Period.ofDays(3));
        samples.put(Year.class, Year.of(2026));
        samples.put(YearMonth.class, YearMonth.of(2026, 5));
        samples.put(MonthDay.class, MonthDay.of(5, 22));
        return samples;
    }

    /**
     * 往復非対称を<b>意図的に</b>許す型と、その理由。
     *
     * <p>空でよい（現在は許可対象なし）。将来「出力だけカスタムしたい」型が出たら、
     * ここに理由を書いて初めて番人を通れる。理由を書かずに通す抜け道は用意しない。</p>
     */
    private static final Map<Class<?>, String> ASYMMETRY_ALLOWED = Map.of();

    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        // TimeZoneConfig と同じ状態を作る（本番の JVM 既定 TZ）
        originalTimeZone = TimeZone.getDefault();
        new TimeZoneConfig().initTimeZone();
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalTimeZone);
    }

    // ============================================================
    // AC-9-1 / AC-9-2
    // ============================================================

    @Test
    @DisplayName("AC-9-1/2: 出力をカスタムした java.time 型は自分の出力を読み戻して元の値に戻る")
    void 出力をカスタムした時刻型は往復で値が変わらない() throws Exception {
        ObjectMapper configured = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());
        SerializerProvider provider = configured.getSerializerProviderInstance();

        List<Class<?>> customized = new ArrayList<>();
        List<String> missingSamples = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (Class<?> type : allJavaTimeTypesUnderWatch()) {
            if (!isCustomSerializer(provider, type)) {
                continue; // Jackson 標準のままの型は本番の欠陥と無関係
            }
            customized.add(type);

            Object sample = SAMPLES.get(type);
            if (sample == null) {
                // AC-9-2: 検体が無い型を黙って検査対象外にしない
                missingSamples.add(type.getName());
                continue;
            }

            try {
                String json = configured.writeValueAsString(sample);
                Object readBack = configured.readValue(json, type);
                if (!sample.equals(readBack)) {
                    violations.add("%s: 往復で値が変わった（出力=%s / 元=%s / 読み戻し=%s）"
                            .formatted(type.getName(), json, sample, readBack));
                }
            } catch (Exception e) {
                violations.add("%s: 自分が書き出した JSON を読み戻せない（%s: %s）"
                        .formatted(type.getName(), e.getClass().getSimpleName(), e.getMessage()));
            }
        }

        // 番人が空回りしていないことの担保（LocalDateTime は必ずカスタム対象である）
        assertThat(customized)
                .as("""
                    出力をカスタムしている java.time 型が 1 つも検出できなかった。
                    JacksonConfig の addSerializer が消えたか、本番人の検出ロジックが壊れている。
                    どちらにせよ「番人が何も守っていない」状態なので必ず調査すること。""")
                .contains(LocalDateTime.class);

        if (!missingSamples.isEmpty()) {
            fail("""
                 出力をカスタムしているのに検体（サンプル値）が未登録の java.time 型がある:
                 %s

                 %s の SAMPLES に検体を追加し、往復が成立することを確かめること。
                 検体が無いまま放置すると、その型は番人の検査対象から静かに漏れる。"""
                    .formatted(String.join("\n", missingSamples),
                            JacksonTimeTypeSymmetryGuardTest.class.getSimpleName()));
        }

        List<String> unexcused = violations.stream()
                .filter(v -> ASYMMETRY_ALLOWED.keySet().stream()
                        .noneMatch(allowed -> v.startsWith(allowed.getName() + ":")))
                .toList();

        if (!unexcused.isEmpty()) {
            fail("""
                 java.time 型の JSON 往復が非対称になっている（Issue #2508 と同型の欠陥）:
                 %s

                 出力（シリアライザ）をカスタムしたなら、対になる入力（デシリアライザ）も
                 JacksonConfig の同じ SimpleModule に addDeserializer で登録すること。
                 片方だけだと「自分の出力を読み戻せない」「ユーザー TZ を無視して値がずれる」という
                 静かなデータ破壊になる（payload_json の読み戻し経路でも実害が出ている）。

                 意図的に片方だけにしたい場合は %s の ASYMMETRY_ALLOWED に理由を書いて登録すること。"""
                    .formatted(String.join("\n", unexcused),
                            JacksonTimeTypeSymmetryGuardTest.class.getSimpleName()));
        }
    }

    // ============================================================
    // AC-9-3
    // ============================================================

    @Test
    @DisplayName("AC-9-3: デシリアライザの SERVER_ZONE と シリアライザが見る systemDefault が一致する")
    void 対になる二つの基準TZ参照が一致している() {
        // シリアライザは ZoneId.systemDefault() を、デシリアライザは定数 SERVER_ZONE を見ている。
        // 参照元が非対称なので、TimeZoneConfig が動くとシリアライザだけが追従して往復が壊れる。
        assertThat(ZoneId.systemDefault())
                .as("""
                    LocalDateTimeTimezoneSerializer が見る ZoneId.systemDefault() と、
                    LocalDateTimeTimezoneDeserializer.SERVER_ZONE が食い違っている。

                    この 2 つは「アプリ層が LocalDateTime をどの壁時計で保持するか」という
                    同一の事実を指しているが、参照元が非対称（JVM 既定 vs 定数）である。
                    TimeZoneConfig の設定値を変えるなら SERVER_ZONE も同時に変えること。
                    片方だけ動かすと、送信は新 TZ・受信は旧 TZ となり往復の対称性が静かに壊れる
                    （日本のユーザーだけ差が 0 になるため発覚が遅れる。Issue #2508 と同じ罠）。""")
                .isEqualTo(LocalDateTimeTimezoneDeserializer.SERVER_ZONE);
    }

    @Test
    @DisplayName("AC-9-3補: TimeZoneConfig はアプリ層の基準 TZ を SERVER_ZONE と同じ値に設定する")
    void TimeZoneConfigの設定値がSERVER_ZONEと一致する() {
        // setUp で new TimeZoneConfig().initTimeZone() を実行済み。
        // 「規約ドキュメントの文字列」ではなく実際に設定される値を検査する
        assertThat(TimeZone.getDefault().toZoneId())
                .as("TimeZoneConfig が設定する JVM 既定 TZ は LocalDateTimeTimezoneDeserializer.SERVER_ZONE と"
                        + "同じでなければならない（.claudecode.md §20 アプリ層＝JST 壁時計）")
                .isEqualTo(LocalDateTimeTimezoneDeserializer.SERVER_ZONE);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** 監視対象の java.time 型（検体の有無に関わらず走査する）。 */
    private static List<Class<?>> allJavaTimeTypesUnderWatch() {
        return List.of(
                LocalDateTime.class, LocalDate.class, LocalTime.class,
                OffsetDateTime.class, OffsetTime.class, ZonedDateTime.class,
                Instant.class, Duration.class, Period.class,
                Year.class, YearMonth.class, MonthDay.class);
    }

    /**
     * 当該型の出力が Jackson 標準から差し替えられているか。
     *
     * <p>「本アプリが自前で書いたシリアライザに解決されるか」で判定する。
     * {@link JavaTimeModule} 由来のシリアライザは {@code com.fasterxml.jackson} 配下なので区別できる。</p>
     */
    private static boolean isCustomSerializer(SerializerProvider provider, Class<?> type) {
        try {
            JsonSerializer<Object> serializer = provider.findValueSerializer(type);
            return serializer != null
                    && serializer.getClass().getName().startsWith("com.mannschaft.app.");
        } catch (Exception e) {
            // シリアライザが解決できない型は本番でも直列化できない。番人の対象外として扱わず失敗させる
            throw new AssertionError(
                    "型 " + type.getName() + " のシリアライザを解決できなかった: " + e.getMessage(), e);
        }
    }
}
