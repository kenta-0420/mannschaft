package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F02.5 行動メモ × タグ 中間テーブルエンティティ。
 *
 * <p>論理削除はなし。メモ/タグの論理削除で十分。
 * BaseEntity は継承せず独自に created_at のみ持つ。</p>
 *
 * <p><b>GDPR 連携</b>: action_memos カテゴリの一部としてエクスポートする。</p>
 */
@PersonalData(category = "action_memos")
@Entity
@Table(name = "action_memo_tag_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ActionMemoTagLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memoId;

    @Column(nullable = false)
    private Long tagId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
