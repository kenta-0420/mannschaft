package com.mannschaft.app.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway FAILED 状態のマイグレーションをクリーンアップするための設定。
 * V9.175 が FAILED 状態で残っている場合、checksum 不一致エラーを防ぐため
 * migrate 前に repair を実行してから migrate する。
 */
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
