package com.mannschaft.app.tournament.roster;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * エントリーテンプレのベンチ役員エンティティ（F08.7.1/05 §8.4）。
 *
 * <p>ベンチ役員を「メンバー表テンプレ」として保存し、apply-template 時に
 * {@link FixtureRosterStaffEntity} へ複製する。構造は match_roster_staff と対応させる。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則6・{@link UuidV7Entity} 継承）。</li>
 *   <li>{@code templateId} は同一 tournament ドメイン内の tournament_entry_templates(id) への参照。
 *       実 DB の物理型は CHAR(36)（案A）だが、ddl-auto テストでは Entity 側の {@code BINARY(16)} 定義で
 *       スキーマ生成される。既存 {@code TournamentEntryTemplateMemberEntity.templateId} と同一規約。</li>
 *   <li>{@code userId} は user ドメインへの ID 参照（クロスドメイン FK なし／原則1・NULL 可）。</li>
 * </ul>
 */
@Entity
@Table(name = "tournament_entry_template_staff")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentEntryTemplateStaffEntity extends UuidV7Entity {

    /** テンプレートID（tournament_entry_templates.id・実体型 CHAR(36)／案A） */
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID templateId;

    /** 役職（監督/コーチ/トレーナー 等） */
    @Column(nullable = false, length = 32)
    private String role;

    /** 氏名（アプリ未登録者も記載可） */
    @Column(nullable = false, length = 128)
    private String name;

    /** 紐付くユーザー（user ドメインへの ID 参照・NULL 可） */
    private Long userId;

    /** 並び順 */
    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = 0;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
