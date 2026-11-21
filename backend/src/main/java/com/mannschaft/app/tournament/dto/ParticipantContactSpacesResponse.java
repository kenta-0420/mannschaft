package com.mannschaft.app.tournament.dto;

import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.service.ContactAccessLevel;

import java.util.List;
import java.util.UUID;

/**
 * 参加者向け連絡スペース一覧の応答（CMP-261118-0900 §10.3.3）。
 *
 * @param tournamentId   大会 ID
 * @param tournamentName 大会名
 * @param divisions      自チームが REGISTERED/ACTIVE で出ている部門（1 件以上）
 * @param spaces         呼び出し者が見られるスペース（0 件もあり得る）
 */
public record ParticipantContactSpacesResponse(
        Long tournamentId,
        String tournamentName,
        List<DivisionItem> divisions,
        List<SpaceItem> spaces) {

    /** 部門。 */
    public record DivisionItem(Long divisionId, String name) {
    }

    /**
     * スペース。
     *
     * @param divisionName 大会全体スペースでは null
     * @param accessLevel  VIEW / POST（権限なしは応答に含めない）
     */
    public record SpaceItem(
            UUID id,
            ContactSpaceScopeType scopeType,
            Long scopeId,
            String divisionName,
            ContactSpaceKind spaceKind,
            Long refId,
            boolean isPublic,
            ContactAccessLevel accessLevel) {
    }
}
