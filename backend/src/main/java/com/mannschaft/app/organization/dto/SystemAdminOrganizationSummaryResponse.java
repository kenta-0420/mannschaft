package com.mannschaft.app.organization.dto;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * システム管理ダッシュボード「全組織一覧」レスポンス DTO（organization ドメイン所有）。
 *
 * <p>{@link OrganizationEntity} 直返しを廃し、システム管理画面で必要な項目のみを明示的に返す。
 * 楽観ロック用 {@code version}・論理削除日時 {@code deletedAt}・拡張プロフィール等の内部項目は含めない。
 * フィールド名は Entity のシリアライズ名と一致させ、フロントエンドを無風化する。</p>
 *
 * <p><b>配置理由（ドメイン境界の原則）</b>: 本 DTO と対応する
 * {@code SystemAdminOrganizationSummaryMapper} は {@link OrganizationEntity}（organization ドメイン）を
 * 参照するため、organization ドメイン内に配置する。admin ドメインへ置くと D-1 クロスドメイン
 * Entity 参照違反となるため、所有権を organization ドメインに置く設計とした。</p>
 */
@Getter
@Builder
public class SystemAdminOrganizationSummaryResponse {

    private final Long id;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final String slug;
    private final String name;
    private final String nameKana;
    private final String nickname1;
    private final String nickname2;
    private final OrganizationEntity.OrgType orgType;
    private final Long parentOrganizationId;
    private final String prefecture;
    private final String city;
    private final OrganizationEntity.Visibility visibility;
    private final OrganizationEntity.HierarchyVisibility hierarchyVisibility;
    private final Boolean supporterEnabled;
    private final LocalDateTime archivedAt;
}
