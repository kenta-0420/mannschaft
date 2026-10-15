package com.mannschaft.app.tournament.submission.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大会提出枠（tournament_submission_requirement）更新リクエスト DTO（F08.7.1/06 §5.1）。
 *
 * <p>締切・対象・支払い条件・表示情報を更新する。form_template_id（書類定義の出所）は変更不可。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateSubmissionRequirementRequest {

    /** 提出枠の表示名。 */
    @Size(max = 255)
    private final String title;

    /** 補足説明。 */
    @Size(max = 2000)
    private final String description;

    /** 対象ディビジョン（NULL = 大会全体）。 */
    private final Long divisionId;

    /** 提出締切（NULL = 締切なし）。 */
    private final LocalDateTime deadline;

    /** 対象範囲。{@code "ALL_TEAMS"} / {@code "SPECIFIC_TEAMS"}。NULL は未変更。 */
    @Pattern(regexp = "ALL_TEAMS|SPECIFIC_TEAMS",
            message = "targetScope は ALL_TEAMS または SPECIFIC_TEAMS のいずれかである必要があります")
    private final String targetScope;

    /** 受理条件に「大会参加費の支払い済み」を課すか（NULL = 未変更）。 */
    private final Boolean requiresPayment;

    /** {@code targetScope = SPECIFIC_TEAMS} のときの対象チーム ID 一覧。 */
    private final List<Long> teamIds;
}
