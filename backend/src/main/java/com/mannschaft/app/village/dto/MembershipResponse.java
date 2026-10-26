package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村メンバーシップレスポンス（F17.1 Phase 1 B3）。
 *
 * <p>表示名は呼び出し元 Service で解決して埋める。Phase 1 では Service 側のスタブ実装で
 * USER は {@code null}（後続足軽がニックネーム解決を担当）、TEAM/ORG は素の ID 文字列を返す。</p>
 *
 * @param id              メンバーシップ ID（村内固有 UUIDv7）
 * @param subjectType     主体種別
 * @param subjectId       主体ID
 * @param displayName     表示名（ニックネーム / チーム名 / 組織名）。Phase 1 では未解決可
 * @param role            村内ロール
 * @param joinedAt        参加日時
 * @param isBanned        BAN 中フラグ
 * @param participationWarn 30 村以上のソフト警告（参加レスポンスにのみ意味あり）
 */
public record MembershipResponse(
        UUID id,
        VillageSubjectType subjectType,
        Long subjectId,
        String displayName,
        VillageRole role,
        LocalDateTime joinedAt,
        boolean isBanned,
        boolean participationWarn
) {

    /**
     * Entity からレスポンス DTO を生成する（表示名解決前）。
     */
    public static MembershipResponse of(VillageMembershipEntity entity, String displayName) {
        return new MembershipResponse(
                entity.getId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                displayName,
                entity.getRole(),
                entity.getJoinedAt(),
                entity.getBannedAt() != null,
                false);
    }

    /**
     * 参加直後のレスポンス用。30 村超過のソフト警告を併載する。
     */
    public static MembershipResponse ofJoined(VillageMembershipEntity entity,
                                              String displayName,
                                              boolean participationWarn) {
        return new MembershipResponse(
                entity.getId(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                displayName,
                entity.getRole(),
                entity.getJoinedAt(),
                entity.getBannedAt() != null,
                participationWarn);
    }
}
