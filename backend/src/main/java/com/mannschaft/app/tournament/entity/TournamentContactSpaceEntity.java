package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 大会・ディビジョン連絡スペースのエンティティ（F08.7.1 連絡機能）。
 *
 * <p>「このスコープ（大会 or ディビジョン）の、この種別（掲示板 or チャット）のスペースが、
 * どの bulletin/chat リソースに払い出されているか」と公開フラグを 1 行で管理する。
 * 冪等化の逆引きキー（{@code (scope_type, scope_id, space_kind)} UNIQUE）も兼ねる。</p>
 *
 * <p>主キーは UUIDv7（原則 6）。{@code scope_id} / {@code ref_id} はクロスドメインの ID 値のみ保持し
 * FK は張らない（原則 1）。整合性はアプリ層で保証する。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/01_communication.md §2.1</p>
 */
@Entity
@Table(name = "tournament_contact_space")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class TournamentContactSpaceEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 30)
    private ContactSpaceScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "space_kind", nullable = false, length = 20)
    private ContactSpaceKind spaceKind;

    /** 払い出した実体の ID（BULLETIN=bulletin_categories.id / CHAT=chat_channels.id）。FK なし（原則1）。 */
    @Column(name = "ref_id", nullable = false)
    private Long refId;

    /** 公開トグル（TRUE で PUBLIC 閲覧可。CHAT も既定 FALSE）。 */
    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = false;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 公開フラグを変更する（公開トグル API・設計書 §5）。
     */
    public void changeVisibility(boolean isPublic) {
        this.isPublic = isPublic;
    }

    /**
     * 論理削除を行う（大会/ディビジョン削除時の archive・設計書 §6.1）。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
