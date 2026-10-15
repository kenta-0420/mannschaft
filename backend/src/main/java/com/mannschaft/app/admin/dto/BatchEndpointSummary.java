package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F10.X 第二陣（汎用バッチキック API）— 登録済みバッチエンドポイントの概要 DTO。
 *
 * <p>{@code GET /api/v1/system-admin/batch} の一覧応答に含めるレコード。
 * 直近実行の status と startedAt は {@code batch_job_logs} の最新 1 件から導出する
 * （実行履歴が無い場合は両方 null）。</p>
 *
 * @param name                バッチ識別子（{@code @BatchEndpoint.name()}）
 * @param description         説明（人間可読、UI 表示用）
 * @param schedulerLockName   {@code @SchedulerLock.name()}（無ければ null）
 * @param lastStatus          直近実行のステータス（"RUNNING"|"SUCCESS"|"FAILED"|"SKIPPED" or null）
 * @param lastStartedAt       直近実行の開始時刻（無ければ null）
 */
public record BatchEndpointSummary(
        String name,
        String description,
        String schedulerLockName,
        String lastStatus,
        LocalDateTime lastStartedAt) {

    /**
     * 直近実行履歴を持たないエンドポイント用のファクトリ。
     */
    public static BatchEndpointSummary withoutHistory(
            String name, String description, String schedulerLockName) {
        return new BatchEndpointSummary(name, description, schedulerLockName, null, null);
    }
}
