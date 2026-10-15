package com.mannschaft.app.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 採点競技（フィギュアスケート/体操）の採点結果記録リクエスト
 * （sports/07_scored.md §4 / §9 / 01 §B.1.2 / §D.8）。
 *
 * <p>MVP は合計点のみ・2 者対戦（HOME/AWAY）。両者の合計点を<b>整数スケール×1000</b>で受け取り、
 * {@code home_score}/{@code away_score} へそのまま格納する（勝敗の正準はスコア列大小・§B.1.2）。
 * 小数⇔整数スケールの変換は FE/DTO 層で行う（例 198.45→198450）。サーバーは整数スケール値を
 * そのまま検証・格納する（コアの集計 {@code resolveResult()} は整数の大小だけを見る・§4.1）。</p>
 *
 * <p>勝敗導出は合計点の高い側が勝者・同点（整数スケール同値）は引分（DRAW・§6）。
 * 採点競技は勝ち方（win_method）・PK・手数の概念を持たない（いずれも NULL・§3 / §10）。</p>
 *
 * <p><b>Schema 命名</b>: tournament ドメインの {@code Match*} と OpenAPI スキーマ名が衝突しないよう
 * {@code MatchRecordScoredResultRequest} を明示する（feedback_spring_bean_name_collision_same_simplename と同趣旨）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4 / §9 / 01 §B.1.2 / §D.8</p>
 */
@Schema(name = "MatchRecordScoredResultRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchScoredResultRequest {

    /**
     * ホーム側の合計点（整数スケール×1000・例 198.45→198450）。
     * 非負かつ INT UNSIGNED の範囲内（範囲外は 400）。
     */
    @Schema(description = "ホーム側合計点（整数スケール×1000・例 198450）", example = "198450")
    @NotNull
    @Min(0)
    @Max(Integer.MAX_VALUE)
    private Integer homeScoreScaled;

    /**
     * アウェイ側の合計点（整数スケール×1000・例 195.30→195300）。
     * 非負かつ INT UNSIGNED の範囲内（範囲外は 400）。
     */
    @Schema(description = "アウェイ側合計点（整数スケール×1000・例 195300）", example = "195300")
    @NotNull
    @Min(0)
    @Max(Integer.MAX_VALUE)
    private Integer awayScoreScaled;
}
