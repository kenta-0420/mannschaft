package com.mannschaft.app.config;

/**
 * データソース種別（プライマリ / レプリカ）。
 * RoutingDataSource のルックアップキーとして使用する。
 */
public enum DataSourceType {
    PRIMARY,
    REPLICA
}
