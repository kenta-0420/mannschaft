package com.mannschaft.app.disclosure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 重要事項説明書 自動削除バッチ実行ログエンティティ（F09.14 Phase 3-E）。
 *
 * <p>{@code disclosure_auto_delete_batch_logs} テーブル（V61.018）に対応。
 * 設計書 §5.7 出力ファイル保管期間（デフォルト 90 日、ADMIN による延長で最大 7 年）に
 * 基づく自動削除バッチの実行結果（対象件数 / 成功件数 / 失敗件数 / エラー詳細）を記録する。</p>
 *
 * <p>本エンティティは {@code created_at} のみ持ち {@code updated_at} / {@code deleted_at} を
 * 持たない（ログレコードは不変）。{@code DisclosureExportEntity} と同パターン。</p>
 */
@Entity
@Table(name = "disclosure_auto_delete_batch_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class DisclosureAutoDeleteBatchLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** バッチ実行開始時刻。 */
    @Column(nullable = false)
    private LocalDateTime batchRunAt;

    /** 期限切れと判定された disclosure_exports レコード総数。 */
    @Column(nullable = false)
    private Integer totalExpired;

    /** 実際に削除に成功した件数（R2 削除 + DB 削除 共に成功）。 */
    @Column(nullable = false)
    private Integer totalDeleted;

    /** 削除失敗件数。 */
    @Column(nullable = false)
    private Integer failedCount;

    /** 失敗時のエラー詳細（例: 「exportId=123: R2 DELETE_FAILED」）。複数件は改行区切り。 */
    @Column(columnDefinition = "TEXT")
    private String errorDetails;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
