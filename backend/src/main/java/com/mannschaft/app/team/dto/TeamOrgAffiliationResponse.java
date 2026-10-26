package com.mannschaft.app.team.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 申請・招待・加盟の共通表現（F01.2.1 §10.4 {@code TeamOrgAffiliationResponse}）。
 *
 * <p>チーム側の申請（2-B1）・組織側の承認と一覧（2-B2）・招待（2-C）・加盟チーム一覧（4-B）が共有する。
 * ネストした型は OpenAPI のスキーマ名が他の DTO と衝突しないよう、固有の名前にしている。</p>
 *
 * @param id           加盟の ID（membershipId）
 * @param status       {@code PENDING} / {@code ACTIVE}
 * @param direction    {@code ORG_INVITE}（組織からの招待）/ {@code TEAM_APPLY}（チームからの申請）
 * @param team         チーム
 * @param organization 組織
 * @param teamGroup    チームグループ。グループ機能が off・未分類・削除済みグループなら null
 * @param message      添え書き（PENDING のときだけ値を持つ）
 * @param requestedBy  PENDING を作った人。退会者は null
 * @param requestedAt  PENDING を作った日時（オフセット付き）
 * @param respondedAt  承諾・承認した日時
 * @param expiresAt    期限（PENDING のときだけ。{@code requestedAt} + 60日）
 */
public record TeamOrgAffiliationResponse(
        Long id,
        String status,
        String direction,
        AffiliationPartyRef team,
        AffiliationPartyRef organization,
        AffiliationGroupRef teamGroup,
        String message,
        AffiliationRequesterRef requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime respondedAt,
        OffsetDateTime expiresAt) {

    /** チーム・組織の表示用の最小情報。 */
    public record AffiliationPartyRef(String slug, String name, String iconUrl) {
    }

    /** チームグループの表示用の最小情報。 */
    public record AffiliationGroupRef(UUID id, String name) {
    }

    /** PENDING を作った人。 */
    public record AffiliationRequesterRef(Long id, String displayName) {
    }
}
