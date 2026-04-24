package com.mannschaft.app.jobmatching.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * F13.1 求人マッチング機能の Spring 設定。
 *
 * <p>現状は QR チェックイン／アウト（Phase 13.1.2）で時間制御を決定論的に扱うため、
 * テスト時に差し替え可能な {@link Clock} Bean を提供する。</p>
 *
 * <p>他機能でも {@link Clock} が必要になった場合は、共通の Config クラスへ昇格させること。</p>
 */
@Configuration
public class JobmatchingConfig {

    /**
     * UTC の壁時計。QR トークン発行・失効判定で使用する。
     * テスト時は {@code @MockBean} もしくは手動で {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} へ差し替える。
     */
    @Bean
    public Clock utcClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
