package com.mannschaft.app.tournament.submission.dto;

import com.mannschaft.app.tournament.submission.TournamentSubmissionRequirementEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 大会提出枠レスポンス DTO（F08.7.1/06）。
 *
 * @param id              提出枠 ID（UUIDv7）
 * @param tournamentId    対象大会 ID
 * @param divisionId      対象ディビジョン ID（NULL = 大会全体）
 * @param formTemplateId  連結 form_template ID
 * @param title           表示名
 * @param description     補足説明
 * @param deadline        提出締切
 * @param targetScope     対象範囲（ALL_TEAMS / SPECIFIC_TEAMS）
 * @param targetTeamIds   SPECIFIC_TEAMS のときの対象チーム一覧（ALL_TEAMS では空）
 * @param requiresPayment 受理条件に大会参加費の支払い済みを課すか
 * @param createdAt       作成日時
 */
public record SubmissionRequirementResponse(
        UUID id,
        Long tournamentId,
        Long divisionId,
        Long formTemplateId,
        String title,
        String description,
        LocalDateTime deadline,
        String targetScope,
        List<Long> targetTeamIds,
        boolean requiresPayment,
        LocalDateTime createdAt
) {
    /**
     * エンティティ・対象チームからレスポンスを組み立てる。
     */
    public static SubmissionRequirementResponse of(TournamentSubmissionRequirementEntity req,
                                                   List<Long> targetTeamIds) {
        return new SubmissionRequirementResponse(
                req.getId(),
                req.getTournamentId(),
                req.getDivisionId(),
                req.getFormTemplateId(),
                req.getTitle(),
                req.getDescription(),
                req.getDeadline(),
                req.getTargetScope().name(),
                targetTeamIds,
                req.isRequiresPayment(),
                req.getCreatedAt()
        );
    }
}
