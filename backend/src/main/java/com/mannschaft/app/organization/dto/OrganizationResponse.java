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

    /** URL 識別子（カスタムスラッグ）。実体は {@code slug} と同値。 */
    private String id;
    /** 組織スラッグ（URL ルーティング用）。{@code /organizations/{slug}} に使用する。 */
    private String slug;
    /**
     * 組織の内部 BIGINT ID（F09.19.10）。
     *
     * <p>URL には使用しない（URL 識別子は上記 {@code id}/{@code slug} が正準）。
     * Spotlight 掲載面 API（{@code GET /api/v1/spotlight/content?scopeType=ORGANIZATION&scopeId=}）等、
     * BE が Long スコープ ID を要求する内部連携専用に公開する。露出先はチーム同様に
     * 当該組織を閲覧可能な者（visibility ラダー準拠）に限られ、cross-domain FK には使わない。</p>
     */
    private Long numericId;
    private OrgBasicInfoDto basicInfo;
    private OrgHierarchyDto hierarchy;
    private OrgLocationDto location;
    private OrgVisibilityDto visibility;
    private OrgMetadataDto metadata;
    private OrgTimestampsDto timestamps;
    /**
     * F01.2.1 §10.1: チームからの加盟申請の受付状況（組織シェル内の申請ボタンの出し分け用）。
     * 受付状況は組織を閲覧できる人なら誰でも見てよい情報（§3.1「受付状況の閲覧」）。
     */
    private TeamApplicationDto teamApplication;
    /**
     * CMP-261004-1942: 組織のソーシャル情報（ヘッダの「サポーター ◯人」）。
     * チーム詳細の {@code social} と同形。サポーターが 0 人でも 0 を返す（null・欠落にしない）。
     */
    private OrgSocialDto social;

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

    /** 組織のソーシャル情報：アクティブな（退会していない）SUPPORTER 所属の人数。 */
    public record OrgSocialDto(
            long supporterCount) {}

    /** チーム加盟の受付状況：受付中か。 */
    public record TeamApplicationDto(
            boolean enabled) {}
}
