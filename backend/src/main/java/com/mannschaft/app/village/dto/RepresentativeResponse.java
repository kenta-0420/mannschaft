package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageRepresentativeEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村代表委任レスポンス（F17 Phase 2 U3）。
 *
 * <p>表示名は呼出し元 Service で解決して詰める。Service の N+1 を抑える狙いで、
 * 一覧返却時はユーザー名を別途バルクで解決してから本 DTO を組み立てる方針。</p>
 *
 * @param id                       代表委任 ID
 * @param villageId                対象村 ID
 * @param membershipId             対象メンバーシップ ID（TEAM/ORG）
 * @param representativeUserId     代表ユーザーID
 * @param representativeDisplayName 代表ユーザー表示名（解決不可なら null）
 * @param grantedByUserId          委任実行 HEADMAN ユーザーID
 * @param grantedByDisplayName     委任実行ユーザー表示名（解決不可なら null）
 * @param grantedAt                委任日時
 * @param revokedAt                取消し日時（現役なら null）
 * @param note                     委任メモ
 */
public record RepresentativeResponse(
        UUID id,
        UUID villageId,
        UUID membershipId,
        Long representativeUserId,
        String representativeDisplayName,
        Long grantedByUserId,
        String grantedByDisplayName,
        LocalDateTime grantedAt,
        LocalDateTime revokedAt,
        String note
) {

    /**
     * Entity と表示名から DTO を組み立てる。
     * 表示名解決は呼出し元責務。N+1 を避けるため、複数件返す場合は事前に
     * userId→displayName マップを構築してから本メソッドを呼ぶこと。
     */
    public static RepresentativeResponse from(VillageRepresentativeEntity e,
                                              String representativeDisplayName,
                                              String grantedByDisplayName) {
        return new RepresentativeResponse(
                e.getId(),
                e.getVillageId(),
                e.getMembershipId(),
                e.getRepresentativeUserId(),
                representativeDisplayName,
                e.getGrantedByUserId(),
                grantedByDisplayName,
                e.getGrantedAt(),
                e.getRevokedAt(),
                e.getNote()
        );
    }
}
