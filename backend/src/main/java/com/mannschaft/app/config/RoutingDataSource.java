package com.mannschaft.app.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * DataSourceContextHolder の値に基づいてプライマリ / レプリカへルーティングする DataSource。
 * コンテキストが未設定（null）の場合はプライマリにフォールバックする。
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceType type = DataSourceContextHolder.getDataSourceType();
        return (type != null) ? type : DataSourceType.PRIMARY;
    }
}
