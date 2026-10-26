package com.mannschaft.app.config;

import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Optional;

/**
 * テスト環境用 ShedLock 設定。
 *
 * <p>テスト実行時は {@code flyway.enabled: false} のため {@code shedlock} テーブルが
 * 存在しない。{@link ShedLockConfig} を test プロファイルで無効化した代わりに、
 * DB に触れない no-op {@link LockProvider} を提供する。
 * これにより {@code @SchedulerLock} を持つバッチが長いテスト実行中に発火しても
 * {@code Table 'mannschaft_test.shedlock' doesn't exist} エラーが発生しなくなる。
 */
@Configuration
@Profile("test")
public class ShedLockTestConfig {

    @Bean
    public LockProvider lockProvider() {
        // テスト環境では DB を使わずロックを常に取得成功させる（no-op unlock）
        return lockConfiguration -> Optional.of(() -> {
        });
    }
}
