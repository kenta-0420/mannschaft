package com.mannschaft.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * アーカイブDB（読み取り専用 RDS）の DataSource 設定。
 * app.archive.db.enabled=true のときのみ有効化される。
 * 本番環境では ARCHIVE_DB_URL / ARCHIVE_DB_USER / ARCHIVE_DB_PASSWORD を設定する。
 */
@Configuration
@ConditionalOnProperty(name = "app.archive.db.enabled", havingValue = "true")
public class ArchiveDataSourceConfig {

    @Bean(name = "archiveDataSource")
    @ConfigurationProperties(prefix = "app.archive.db")
    public DataSource archiveDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "archiveJdbcTemplate")
    public JdbcTemplate archiveJdbcTemplate(@Qualifier("archiveDataSource") DataSource archiveDataSource) {
        return new JdbcTemplate(archiveDataSource);
    }
}
