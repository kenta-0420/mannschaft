package com.mannschaft.app.match.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 最終スコア確定リクエスト（作成者/記録係/主体チーム ADMIN のみ・03 §C.2）。
 *
 * <p>本戦スコア（home/away）と PK 戦スコア（home/away penalty）を分離して受ける（01 §B.1・sports/01_soccer §4.1）。
 * 各値に {@code @Min(0)}/{@code @Max(999)} を課す（03 §C.4b）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C.2 / §C.4b</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class FinalizeScoreRequest {

    @Min(0)
    @Max(999)
    private Integer homeScore;

    @Min(0)
    @Max(999)
    private Integer awayScore;

    @Min(0)
    @Max(999)
    private Integer homePenaltyScore;

    @Min(0)
    @Max(999)
    private Integer awayPenaltyScore;
}
