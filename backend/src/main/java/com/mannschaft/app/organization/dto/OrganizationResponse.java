package com.mannschaft.app.organization.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 組織詳細レスポンス。
 *
 * <p>ネスト DTO 設計により、フラット構造から意味的にグルーピングされた構造に移行。
 * JSON レスポンスは各グループキー（basicInfo, hierarchy, location, visibility,
 * metadata, timestamps）配下にフィールドがネストされる。</p>
 */
@Builder(toBuilder = true)
@Getter
public class OrganizationResponse {

    /** URL 識別子（カスタムスラッグ）。 */
    private String id;
    /** 組織スラッグ（URL ルーティング用）。{@code /organizations/{slug}} に使用する。 */
    private String slug;
    private OrgBasicInfoDto basicInfo;
    private OrgHierarchyDto hierarchy;
    private OrgLocationDto location;
    private OrgVisibilityDto visibility;
    private OrgMetadataDto metadata;
    private OrgTimestampsDto timestamps;

    /** 組織基本情報：名称・読み仮名・ニックネーム。 */
    public record OrgBasicInfoDto(
            String name,
            String nameKana,
            String nickname1,
            String nickname2) {}

    /** 組織階層情報：組織種別・親組織 ID。 */
    public record OrgHierarchyDto(
            String orgType,
            Long parentOrganizationId) {}

    /** 組織所在地情報：都道府県・市区町村。 */
    public record OrgLocationDto(
            String prefecture,
            String city) {}

    /** 組織公開設定：公開範囲・階層公開範囲・サポーター機能有効化。 */
    public record OrgVisibilityDto(
            String visibility,
            String hierarchyVisibility,
            Boolean supporterEnabled) {}

    /** 組織メタデータ：バージョン・メンバー数・アイコン URL・バナー URL。 */
    public record OrgMetadataDto(
            Long version,
            int memberCount,
            String iconUrl,
            String bannerUrl) {}

    /** 組織タイムスタンプ：アーカイブ日時・作成日時。 */
    public record OrgTimestampsDto(
            LocalDateTime archivedAt,
            LocalDateTime createdAt) {}
}
