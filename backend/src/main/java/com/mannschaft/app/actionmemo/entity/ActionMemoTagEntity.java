package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F02.5 行動メモ タグマスタエンティティ。
 *
 * <p>ユーザー所有のタグ名前空間。他人と共有しない。
 * Phase 1 では Entity / Repository のみ先行作成し、Service / Controller は Phase 4 で実装する。</p>
 *
 * <p><b>GDPR 連携</b>: {@code @PersonalData(category = "action_memos")} で
 * 行動メモ本体と同一カテゴリに束ねてエクスポートする。</p>
 */
@PersonalData(category = "action_memos")
@Entity
@Table(name = "action_memo_tags")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActionMemoTagEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Setter
    @Column(nullable = false, length = 50)
    private String name;

    @Setter
    @Column(length = 7)
    private String color;

    @Setter
    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private LocalDateTime deletedAt;

    /**
     * タグを論理削除する。
     * 中間テーブル（action_memo_tag_links）は残す（過去メモには削除済みタグとして表示）。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
