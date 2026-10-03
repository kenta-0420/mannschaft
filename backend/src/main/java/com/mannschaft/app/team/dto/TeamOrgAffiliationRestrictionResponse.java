package com.mannschaft.app.team.dto;

import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationPartyRef;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 止めた側が見る制限の1件（F01.2.1 §5.4「止めた側の ADMIN だけが種別・理由・期限を見られる」・§10.1）。
 *
 * <p>組織側の一覧（組織が止めている申請）と、チーム側の一覧（チームが止めている招待）が共有する。</p>
 *
 * @param id              制限 ID（解除の API に渡す）
 * @param direction       止めている向き（組織側は {@code TEAM_APPLY}、チーム側は {@code ORG_INVITE}）
 * @param kind            {@code COOLDOWN} / {@code BLOCK}
 * @param reason          {@code REJECTED}（組織側）/ {@code DECLINED}（チーム側）
 * @param restrictedUntil COOLDOWN の期限（オフセット付き）。BLOCK は null
 * @param team            止められているチーム
 * @param organization    止められている組織
 * @param createdAt       制限を記録した日時
 */
public record TeamOrgAffiliationRestrictionResponse(
        UUID id,
        String direction,
        String kind,
        String reason,
        OffsetDateTime restrictedUntil,
        AffiliationPartyRef team,
        AffiliationPartyRef organization,
        OffsetDateTime createdAt) {
}
