package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.HomeAway;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 試合メタ情報の更新リクエスト（日時・会場・相手・試合長など・03 §C.2）。
 *
 * <p>{@code teamId}/{@code createdBy}/{@code organizationId}/{@code status}/スコアは本 DTO に含めない。
 * status 遷移は {@code PATCH .../status}、スコア確定は {@code PATCH .../score}、記録モードは {@code PATCH .../recording-mode}
 * の専用エンドポイントへ分離する（責務分界・改竄耐性）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C.2 / §C.4b</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateMatchRequest {

    private HomeAway homeAway;

    /** 登録相手チーム（team ドメイン ID 参照・null 可）。 */
    private Long opponentTeamId;

    @Size(max = 128)
    private String opponentName;

    private LocalDateTime kickoffAt;

    @Size(max = 200)
    private String venue;

    @Min(0)
    private Integer durationMinutes;

    @Size(max = 32)
    private String periodFormat;

    @Size(max = 2000)
    private String notes;
}
