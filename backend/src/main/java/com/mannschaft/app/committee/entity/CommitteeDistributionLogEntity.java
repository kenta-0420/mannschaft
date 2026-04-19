package com.mannschaft.app.committee.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F04.10 委員会伝達処理ログエンティティ。
 *
 * <p>委員会からの伝達（お知らせ配信・確認通知送信）の実行履歴を記録する。
 * このテーブルは updated_at カラムを持たないため、BaseEntity の updatedAt を
 * insertable=false / updatable=false の仮想カラムとして上書きしている。</p>
 */
@Entity
@Table(name = "committee_distribution_logs")
// updated_at カラムが DDL に存在しないため、BaseEntity の updatedAt マッピングを無効化する
@AttributeOverrides({
    @AttributeOverride(name = "updatedAt",
        column = @Column(name = "updated_at", insertable = false, updatable = false))
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CommitteeDistributionLogEntity extends BaseEntity {

    /** 委員会 ID（FK → committees） */
    @Column(nullable = false)
    private Long committeeId;

    /**
     * 伝達対象種別。
     * SURVEY_RESULT / ACTIVITY_RECORD / CIRCULATION_RESULT / CUSTOM_MESSAGE のいずれか。
     */
    @Column(nullable = false, length = 30)
    private String contentType;

    /** 伝達対象エンティティ ID（CUSTOM_MESSAGE の場合 null） */
    @Column
    private Long contentId;

    /** CUSTOM_MESSAGE 時のタイトル（最大 200 文字） */
    @Column(length = 200)
    private String customTitle;

    /** CUSTOM_MESSAGE 時の本文 */
    @Column(columnDefinition = "TEXT")
    private String customBody;

    /** 配信先スコープ */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DistributionScope targetScope;

    /** お知らせフィードに投下したかどうか */
    @Column(nullable = false)
    private boolean announcementEnabled;

    /** 確認通知モード */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConfirmationMode confirmationMode;

    /** 生成された確認通知 ID（FK → confirmable_notifications、ON DELETE SET NULL） */
    @Column
    private Long confirmableNotificationId;

    /** 生成された announcement_feeds レコードの ID 配列（JSON 文字列として保存） */
    @Column(columnDefinition = "JSON")
    private String announcementFeedIds;

    /** 伝達操作者（FK → users、ON DELETE SET NULL） */
    @Column
    private Long createdBy;

    /**
     * お知らせフィード ID リスト（JSON 文字列）と確認通知 ID を後付けで設定する。
     * お知らせフィード生成後に呼び出す。
     *
     * @param announcementFeedIds お知らせフィード ID の JSON 文字列
     * @param confirmableNotificationId 確認通知 ID
     */
    public void applyGeneratedIds(String announcementFeedIds, Long confirmableNotificationId) {
        this.announcementFeedIds = announcementFeedIds;
        this.confirmableNotificationId = confirmableNotificationId;
    }
}
