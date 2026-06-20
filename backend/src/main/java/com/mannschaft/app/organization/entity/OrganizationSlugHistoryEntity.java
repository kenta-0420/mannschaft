package com.mannschaft.app.organization.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 組織 slug リネーム履歴エンティティ（F01.2 §5.9.5）。
 *
 * <p>組織の slug を変更した際に「旧 slug」を記録する。旧 URL アクセスを新 slug へ
 * 301 リダイレクトするための解決元データ（{@code old_slug → 現 organization.slug}）であり、
 * かつ他組織が同じ slug を再利用できないよう恒久的に予約する役割を持つ。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則 6・{@link UuidV7Entity} 継承）。id は BINARY(16)。</li>
 *   <li>{@code organizationId} は organizations.id への ID 参照のみ。クロスドメイン FK は張らない（原則 1）。
 *       参照整合性はアプリ層で保証する。</li>
 *   <li>履歴は恒久保持（301 解決と再利用予約のため）。論理削除カラムは持たない。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F01.2_org_team_member_role/04_security_operations.md §5.9.5</p>
 */
@Entity
@Table(name = "organization_slug_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class OrganizationSlugHistoryEntity extends UuidV7Entity {

    /** リネーム対象組織（organizations.id への ID 参照・FK なし／原則1） */
    @Column(nullable = false)
    private Long organizationId;

    /** リネーム前の旧 slug（恒久予約・301 解決のキー） */
    @Column(name = "old_slug", length = 30, nullable = false, unique = true)
    private String oldSlug;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
