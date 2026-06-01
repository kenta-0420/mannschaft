package com.mannschaft.app.tournament.leaguetransfer.dto;

import com.mannschaft.app.tournament.leaguetransfer.LeagueTransferEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * リーグ移籍レスポンス DTO（F08.7.1 / 03）。
 *
 * @param id                 移籍 ID（UUIDv7）
 * @param direction          方向（PROMOTION / RELEGATION）
 * @param teamId             移籍対象チーム ID
 * @param fromOrganizationId 手放す側 org ID
 * @param toOrganizationId   受け入れる側 org ID
 * @param sourceDivisionId   移籍元ディビジョン ID（NULL 許容）
 * @param targetDivisionId   移籍先ディビジョン ID（配属確定後にセット・NULL 許容）
 * @param season             シーズン識別子
 * @param finalRank          移籍元最終順位（NULL 許容）
 * @param status             状態（DISPATCHED / PLACED / DECLINED / CANCELLED）
 * @param initiatedBy        起票者 user_id（証跡）
 * @param respondedBy        応答者 user_id（証跡・NULL 許容）
 * @param message            送り出しメッセージ（NULL 許容）
 * @param createdAt          起票日時
 * @param respondedAt        応答日時（NULL 許容）
 */
public record LeagueTransferResponse(
        UUID id,
        String direction,
        Long teamId,
        Long fromOrganizationId,
        Long toOrganizationId,
        Long sourceDivisionId,
        Long targetDivisionId,
        String season,
        Integer finalRank,
        String status,
        Long initiatedBy,
        Long respondedBy,
        String message,
        LocalDateTime createdAt,
        LocalDateTime respondedAt
) {
    /**
     * エンティティからレスポンスを組み立てる。
     */
    public static LeagueTransferResponse of(LeagueTransferEntity e) {
        return new LeagueTransferResponse(
                e.getId(),
                e.getDirection().name(),
                e.getTeamId(),
                e.getFromOrganizationId(),
                e.getToOrganizationId(),
                e.getSourceDivisionId(),
                e.getTargetDivisionId(),
                e.getSeason(),
                e.getFinalRank(),
                e.getStatus().name(),
                e.getInitiatedBy(),
                e.getRespondedBy(),
                e.getMessage(),
                e.getCreatedAt(),
                e.getRespondedAt()
        );
    }
}
