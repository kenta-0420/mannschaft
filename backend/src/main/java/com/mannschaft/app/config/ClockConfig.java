package com.mannschaft.app.config;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * アプリケーション共通の {@link Clock} Bean 定義。
 *
 * <p>{@code jobmatching/config/JobmatchingConfig} で定義していた {@code utcClock} Bean を、
 * 複数機能が利用するようになったため共通 Config クラスへ昇格させた
 * （JobmatchingConfig javadoc の「他機能でも Clock が必要になった場合は共通の Config クラスへ昇格させること」指示に従う）。</p>
 *
 * <p>利用箇所:</p>
 * <ul>
 *   <li>jobmatching — QR チェックイン／アウトトークンの発行・失効判定（{@code JobQrTokenService}）</li>
 *   <li>F08.9 P3a 後見切替 — 国別後見切替年齢ポリシーの境界日判定（{@code GuardianshipSwitchService}）</li>
 * </ul>
 *
 * <p>テスト時は {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} に差し替えて
 * 決定論的な日時検証ができる（{@code @MockBean} または手動差し替え）。
 * CI を固定日付で塞がないためにも、テストでは必ず固定 Clock を使用すること。</p>
 */
@Configuration
public class ClockConfig {

    /**
     * UTC の壁時計。QR トークン発行・失効判定および後見切替年齢判定で使用する。
     *
     * <p>年齢ポリシー（{@code JapanGuardianshipAgePolicy} 等）は受け取った Clock を
     * 必要に応じて適切なゾーン（JST 等）に再ゾーンして評価するため、
     * 本 Bean のゾーンが判定結果に直接影響することはない。</p>
     */
    @Bean
    @Primary
    public Clock utcClock() {
        return Clock.system(ZoneOffset.UTC);
    }

    /**
     * アプリケーションの<b>壁時計</b>（業務ローカル時刻の Clock）。
     *
     * <p><b>なぜ UTC Clock と別に必要か:</b> DB の {@code LocalDateTime} 列
     * （{@code blog_posts.published_at} 等）は JVM 既定ゾーン基準の壁時計として書かれている。
     * これを UTC 固定の {@link #utcClock()} と直接比較すると、既定ゾーンが UTC でない環境
     * （本番・開発機ともに JST）でオフセット分（+9h）ずれ、予約公開の判定が 9 時間狂う
     * （{@code ReservationPendingExpireService#findExpirableUnits} の Javadoc に実測記録あり）。</p>
     *
     * <p>そこで基準ゾーンを {@link UserZoneLocalDateTimeParser#SERVER_ZONE}
     * （アプリ層の壁時計ゾーンの<b>唯一の正</b>）から組み立てる。{@code ZoneId.systemDefault()} を
     * 呼ばないため暗黙のゾーン依存が無く（番人 {@code DateTimeAndZoneGuardTest} / CMP-023）、
     * {@code TimeZoneConfig}（JVM 既定ゾーン）・本 Bean（壁時計 Clock）・
     * {@link UserZoneLocalDateTimeParser}（API 入力のパーサ）の三者が同一のソースを見るため、
     * 食い違いは構造的に起こらない。</p>
     *
     * <p><b>ゾーンを環境変数で可変にはしない。</b> 実行時に差し替えられるようにしても、
     * DB に既に書かれた {@code LocalDateTime} 列は旧ゾーンの壁時計のままであり、
     * 変えた瞬間に既存データの解釈が壊れる（「設定できるように見えて変えると壊れるつまみ」）。
     * テナント別 TZ の導入は格納形式・入力変換・判定基準を一体で設計し直す必要があり、
     * <b>CMP-023「時刻設計の全域是正＋テナント TZ 導入」</b>が受け皿である。
     * その際は本メソッドが差し替え地点になる。</p>
     *
     * <p>{@code @Primary} は付けない。壁時計が要るのは「JVM 既定ゾーン基準の LocalDateTime 列と
     * 突き合わせる処理」だけであり、必要な箇所が {@code @Qualifier("wallClock")} で明示的に
     * 選ぶべきだからである（取り違えは 9 時間ずれとして表面化する）。</p>
     */
    @Bean
    public Clock wallClock() {
        return Clock.system(UserZoneLocalDateTimeParser.SERVER_ZONE);
    }
}
