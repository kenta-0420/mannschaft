package com.mannschaft.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * リードレプリカへの自動ルーティング DataSource 設定。
 * {@code app.datasource.replica.enabled=true} のときのみ有効化される。
 * 未設定（デフォルト）ではこの Configuration は登録されず、
 * Spring Boot の自動設定による単一 DataSource がそのまま使われる。
 */
@Configuration
@ConditionalOnProperty(name = "app.datasource.replica.enabled", havingValue = "true")
public class DataSourceConfig {

    /**
     * プライマリ DataSource（書き込み用）。
     * spring.datasource.hikari.* の設定値を使用する。
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * レプリカ DataSource（読み取り専用）。
     * app.datasource.replica.* の設定値（DB_REPLICA_URL など）を使用する。
     */
    @Bean
    @ConfigurationProperties(prefix = "app.datasource.replica")
    public DataSource replicaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * プライマリ / レプリカへ動的にルーティングする DataSource。
     * DataSourceContextHolder の値に基づいて接続先を切り替える。
     */
    @Primary
    @Bean
    public DataSource routingDataSource(DataSource primaryDataSource,
                                        DataSource replicaDataSource) {
        RoutingDataSource routing = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.PRIMARY, primaryDataSource);
        targets.put(DataSourceType.REPLICA, replicaDataSource);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primaryDataSource);
        return routing;
    }
}
