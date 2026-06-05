package com.mannschaft.app.config;

import org.springframework.context.annotation.Bean;
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
    public Clock utcClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
