package com.mannschaft.app.config;

/**
 * スレッドローカルでデータソース種別を保持するホルダー。
 * ReplicaRoutingAspect がトランザクション開始前にセットし、
 * 終了後に clear() を呼んで必ずリセットする。
 */
public class DataSourceContextHolder {

    private static final ThreadLocal<DataSourceType> context = new ThreadLocal<>();

    public static void setDataSourceType(DataSourceType type) {
        context.set(type);
    }

    public static DataSourceType getDataSourceType() {
        return context.get();
    }

    public static void clear() {
        context.remove();
    }
}
