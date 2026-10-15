package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.TeamSide;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ターン制（将棋/囲碁）の対局結果記録リクエスト（sports/05_shogi.md §8.1 / sports/06_go.md §8.1 / 01 §B.1.2）。
 *
 * <p>勝者サイドと勝ち方を受け取り、サーバーが {@code home_score}/{@code away_score} に 1-0/0-1/0-0 を導出する
 * （クライアントのスコア直接指定は受け取らない・勝敗の正準はスコア列大小・§B.1.2）。{@code winnerSide}=NULL は
 * 引分（千日手/持将棋/持碁）で、その場合 {@code winMethod} は付けられない（責務分離・§4.2）。</p>
 *
 * <p><b>Schema 命名</b>: tournament ドメインの {@code Match*} と OpenAPI スキーマ名が衝突しないよう
 * {@code MatchRecordTurnResultRequest} を明示する（feedback_spring_bean_name_collision_same_simplename と同趣旨）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/05_shogi.md §4 / §8.1 / 01 §B.1.2 / §D.7</p>
 */
@Schema(name = "MatchRecordTurnResultRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchTurnResultRequest {

    /** 勝者サイド（HOME=先手/黒・AWAY=後手/白・NULL=引分け）。 */
    @Schema(description = "勝者サイド（HOME/AWAY・NULL=引分け）")
    private TeamSide winnerSide;

    /**
     * 勝ち方の enum 名（ShogiWinMethod/GoWinMethod・引分けは NULL）。
     * 列挙値検証は Service（WinMethodCatalog）が当該競技カタログで行う（列挙外は 400）。
     */
    @Schema(description = "勝ち方（ShogiWinMethod/GoWinMethod の enum 名・引分けは null）")
    @Size(max = 32)
    private String winMethod;

    /** 総手数（任意・NULL=不明）。 */
    @Schema(description = "総手数（任意）")
    @Min(0)
    @Max(1000)
    private Integer totalMoves;
}
