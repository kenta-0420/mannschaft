package com.mannschaft.app.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway の起動戦略: 事前検査 → repair → migrate。
 *
 * <p>V9.175 が FAILED 状態で残っている場合、checksum 不一致エラーを防ぐため migrate 前に repair を実行してから migrate する。</p>
 *
 * <p>CMP-260909-2152: α（組織の締め日・締め期間）の migration の事前検査（{@link ShiftBudgetClosePeriodPreflight}）を
 * <b>repair より前</b>に行う。異常（work_date を復元できない消化行・異常な旧月次仕訳・旧 #4560 の V261 表の残存）があれば
 * 例外で起動を止め、スキーマ・データ・履歴とも不変のまま停止できる。</p>
 */
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            ShiftBudgetClosePeriodPreflight.check(flyway);
            flyway.repair();
            flyway.migrate();
        };
    }
}
