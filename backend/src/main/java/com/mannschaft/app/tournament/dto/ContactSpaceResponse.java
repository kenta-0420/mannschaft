package com.mannschaft.app.tournament.dto;

import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;

import java.util.UUID;

/**
 * 連絡スペースのレスポンス（F08.7.1 §5.1）。
 *
 * @param id         スペース ID（UUIDv7）
 * @param scopeType  スコープ種別
 * @param scopeId    スコープ ID（大会 ID / ディビジョン ID）
 * @param spaceKind  種別（BULLETIN / CHAT）
 * @param refId      払い出し先実体 ID（bulletin category id / chat channel id）
 * @param isPublic   公開フラグ
 */
public record ContactSpaceResponse(
        UUID id,
        ContactSpaceScopeType scopeType,
        Long scopeId,
        ContactSpaceKind spaceKind,
        Long refId,
        boolean isPublic) {

    public static ContactSpaceResponse from(TournamentContactSpaceEntity e) {
        return new ContactSpaceResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getSpaceKind(),
                e.getRefId(),
                Boolean.TRUE.equals(e.getIsPublic()));
    }
}
