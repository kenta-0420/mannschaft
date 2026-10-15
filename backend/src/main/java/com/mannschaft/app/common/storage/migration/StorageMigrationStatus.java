package com.mannschaft.app.common.storage.migration;

import java.util.Map;

/**
 * F13 Phase 5-b ストレージパス移行進捗レスポンス。
 *
 * @param totalByFeature    feature_type ごとの総件数
 * @param migratedByFeature feature_type ごとの移行済み（新パス）件数
 * @param pendingByFeature  feature_type ごとの移行未完了（旧パス残存）件数
 * @param errorCount        {@code storage_migration_errors} の未解決件数
 * @param status            全体ステータス（"COMPLETED" / "IN_PROGRESS" / "PENDING"）
 */
public record StorageMigrationStatus(
        Map<String, Long> totalByFeature,
        Map<String, Long> migratedByFeature,
        Map<String, Long> pendingByFeature,
        long errorCount,
        String status
) {}
