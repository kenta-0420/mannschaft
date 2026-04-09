package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gdpr.PersonalData;
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
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F02.5 行動メモ本体エンティティ。
 *
 * <p>ユーザー個人の行動ログ。必須項目は content のみ。
 * タイトル無し・タグ無し・TODO 紐付け無しでも作成可能（摩擦ゼロ原則）。</p>
 *
 * <p><b>GDPR 連携</b>: {@code @PersonalData(category = "action_memos")} により
 * {@code PersonalDataCollector} の網羅性チェックに組み込まれる。</p>
 */
@PersonalData(category = "action_memos")
@Entity
@Table(name = "action_memos")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActionMemoEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Setter
    @Column(nullable = false)
    private LocalDate memoDate;

    @Setter
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ActionMemoMood mood;

    @Setter
    private Long relatedTodoId;

    @Setter
    private Long timelinePostId;

    private LocalDateTime deletedAt;

    /**
     * 行動メモを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
