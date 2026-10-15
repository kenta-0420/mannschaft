package com.mannschaft.app.tournament.submission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大会提出枠（tournament_submission_requirement）作成リクエスト DTO（F08.7.1/06 §3）。
 *
 * <p>書類定義（必要書類・フィールド・添付要否）は F05.6 の {@code form_templates}（{@code formTemplateId} で参照）が
 * 一元管理する。本 DTO は「どの大会／ディビジョンに、どの form_template を、誰を対象に、いつまでに」提出させるかの
 * 薄い連結情報のみを受け取る。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateSubmissionRequirementRequest {

    /** 使用する form_template（F05.6 で作成済み・主催組織に属すること）。 */
    @NotNull
    private final Long formTemplateId;

    /** 提出枠の表示名（例「参加申込書」「選手登録一覧」）。 */
    @NotBlank
    @Size(max = 255)
    private final String title;

    /** 補足説明。 */
    @Size(max = 2000)
    private final String description;

    /** 対象ディビジョン（NULL = 大会全体）。 */
    private final Long divisionId;

    /** 提出締切（NULL = 締切なし）。 */
    private final LocalDateTime deadline;

    /**
     * 対象範囲。{@code "ALL_TEAMS"} / {@code "SPECIFIC_TEAMS"}。NULL は ALL_TEAMS 扱い。
     *
     * <p>不正値による 500 化を避けるため {@code @Pattern} で 400 に倒す（NULL は検証対象外＝許容）。</p>
     */
    @Pattern(regexp = "ALL_TEAMS|SPECIFIC_TEAMS",
            message = "targetScope は ALL_TEAMS または SPECIFIC_TEAMS のいずれかである必要があります")
    private final String targetScope;

    /** 受理条件に「大会参加費の支払い済み」を課すか（領域⑦連携・NULL = false）。 */
    private final Boolean requiresPayment;

    /** {@code targetScope = SPECIFIC_TEAMS} のときの対象チーム ID 一覧。 */
    private final List<Long> teamIds;
}
