package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F08.7 Phase 10-β: 通知失敗 / hook 失敗イベントエンティティ。
 *
 * <p>9-δ AFTER_COMMIT hook の swallow パターンが残しがちだったサイレント失敗を、
 * 専用テーブルで永続化する。{@code retry_count < 3} の範囲で
 * {@link com.mannschaft.app.shiftbudget.batch.ShiftBudgetRetryBatchJob}
 * が 15 分毎に再実行を試みる。</p>
 *
 * <p>設計書 F08.7 (v1.3 追補) §13 Phase 10-β / V11.038 に準拠。</p>
 */
@Entity
@Table(
        name = "shift_budget_failed_events",
        indexes = {
                @Index(name = "idx_sbfe_org_status", columnList = "organization_id, status"),
                @Index(name = "idx_sbfe_pending", columnList = "status, last_retried_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftBudgetFailedEventEntity extends BaseEntity {

    /** FK → organizations。ON DELETE CASCADE */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** イベント種別。CHECK 制約で 5 種類に固定。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private ShiftBudgetFailedEventType eventType;

    /**
     * 対応するソース ID（{@code allocation_id} / {@code alert_id} / {@code shift_schedule_id} など、
     * イベント種別で意味が変わる）。NULL 許容。
     */
    @Column(name = "source_id")
    private Long sourceId;

    /** 元イベントペイロード（再実行用、JSON 文字列）。 */
    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    /** 失敗時のエラーメッセージ（例外クラス名 + メッセージなど）。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** リトライ回数。0 で初回記録、3 で EXHAUSTED 遷移。 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /** 最後にリトライバッチが処理した時刻。次回スキャンの基準として使う。 */
    @Column(name = "last_retried_at")
    private LocalDateTime lastRetriedAt;

    /** ステータス。CHECK 制約で 5 種類に固定。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShiftBudgetFailedEventStatus status;

    /**
     * リトライ着手をマークする（PENDING → RETRYING + retry_count++ + last_retried_at セット）。
     * <p>バッチ Job が排他取得 → 試行 の流れで使う。</p>
     */
    public void markRetrying() {
        this.status = ShiftBudgetFailedEventStatus.RETRYING;
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
        this.lastRetriedAt = LocalDateTime.now();
    }

    /** リトライ成功をマーク（SUCCEEDED 終端ステータス）。 */
    public void markSucceeded() {
        this.status = ShiftBudgetFailedEventStatus.SUCCEEDED;
    }

    /** リトライ失敗をマーク（次回試行可、retry_count が上限なら EXHAUSTED）。 */
    public void markFailed(String error, int maxRetry) {
        this.errorMessage = error;
        if (this.retryCount != null && this.retryCount >= maxRetry) {
            this.status = ShiftBudgetFailedEventStatus.EXHAUSTED;
        } else {
            // 次回バッチ走行で再評価できるように PENDING へ戻す
            this.status = ShiftBudgetFailedEventStatus.PENDING;
        }
    }

    /** 運用者が「手動補正済」をマーク（MANUAL_RESOLVED 終端ステータス）。 */
    public void markManualResolved() {
        this.status = ShiftBudgetFailedEventStatus.MANUAL_RESOLVED;
    }
}
