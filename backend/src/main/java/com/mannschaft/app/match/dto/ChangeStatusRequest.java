package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.MatchStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * status 遷移リクエスト（作成者/記録係/主体チーム ADMIN のみ・03 §C.2）。
 *
 * <p>COMPLETED 遷移時は {@code duration_minutes} 必須（未設定なら 400・02 §E.3）。これは Service 層が検証する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §E.3</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ChangeStatusRequest {

    @NotNull
    private MatchStatus status;
}
