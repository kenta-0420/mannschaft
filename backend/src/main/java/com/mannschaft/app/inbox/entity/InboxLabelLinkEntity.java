package com.mannschaft.app.inbox.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.gdpr.PersonalData;
import com.mannschaft.app.inbox.InboxSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：ラベル↔通知 多対多リンク。
 *
 * <p>ラベル（{@code notification_labels.id}）と通知（{@code (source_type, source_id)} で論理参照）の関連。
 * 手本は {@code ActionMemoTagLinkEntity}（ただし id を BIGINT IDENTITY → UUIDv7 に・原則6）。
 * 論理削除なし（本体削除で十分）。1 通知あたりラベル上限 10（サービス層検証）。</p>
 *
 * <p>{@code label_id} / {@code user_id} / {@code source_id} への FK 制約は張らない（CLAUDE.md 原則1・
 * {@code label_id} は同一 inbox ドメイン内だが一貫性のため不採用）。設計書: 01_data_model.md §2.3。</p>
 *
 * <p><b>GDPR 連携</b>: {@code @PersonalData(category = "inbox")} により
 * インボックス3表として {@code inbox.json} に束ねてエクスポートされる。設計書: 04_security_operations.md。</p>
 */
@PersonalData(category = "inbox")
@Entity
@Table(name = "inbox_label_links")
@Getter
@Setter
@NoArgsConstructor
public class InboxLabelLinkEntity extends UuidV7Entity {

    /** notification_labels.id（同一 inbox ドメイン内・FK なし方針） */
    @Column(name = "label_id", nullable = false)
    private UUID labelId;

    /** 所有ユーザーID（冗長保持・user 絞り込み高速化／所有検証。FK 張らない） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 通知ソース種別 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private InboxSourceType sourceType;

    /** 各ソースPK（論理参照） */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** 作成日時（自動設定、更新不可） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
