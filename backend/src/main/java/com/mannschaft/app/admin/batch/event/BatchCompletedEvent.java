package com.mannschaft.app.admin.batch.event;

import com.mannschaft.app.admin.entity.BatchJobLogEntity;

import java.time.Instant;

/**
 * F10.X 第一陣 — バッチ正常完了イベント。
 *
 * <p>{@link com.mannschaft.app.admin.batch.BatchExecutionAspect} が
 * {@link com.mannschaft.app.admin.batch.BatchEndpoint @BatchEndpoint} 付きメソッドの
 * 正常終了時に発火する。受信側（{@link com.mannschaft.app.admin.batch.BatchEventListener}）は
 * SYSTEM_ADMIN への通知配信を非同期で行う。</p>
 *
 * @param name       バッチ識別子
 * @param log        書き込んだ {@link BatchJobLogEntity}
 * @param occurredAt 発火時刻
 */
public record BatchCompletedEvent(
        String name,
        BatchJobLogEntity log,
        Instant occurredAt) {
}
