package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 大会エントリーテンプレートエンティティ。
 *
 * <p>チームごとによく使うエントリーメンバー構成を保存するテンプレートを表す。
 * team_id / created_by は users/teams テーブルへのクロスドメイン参照のため FK を持たない。
 * 論理削除は deleted_at カラムで管理し、@Where は使用しない（明示的クエリ設計）。</p>
 */
@Entity
@Table(name = "tournament_entry_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentEntryTemplateEntity extends UuidV7Entity {

    /** クロスドメイン参照: teams.id（FK なし、アプリ層で整合性保証） */
    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Short sortOrder = (short) 0;

    /** クロスドメイン参照: users.id（FK なし、アプリ層で整合性保証） */
    @Column(nullable = false)
    private Long createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** 論理削除用タイムスタンプ */
    private LocalDateTime deletedAt;
}
