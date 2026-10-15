package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.ScoredApparatus;
import com.mannschaft.app.match.domain.ScoredComponentType;
import com.mannschaft.app.match.domain.TeamSide;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 採点競技（フィギュアスケート/体操）の<b>審判別/種目別採点内訳</b>記録リクエスト
 * （sports/07_scored.md §4B / 01 §B.1.2 / §D.8）。
 *
 * <p>内訳明細の一覧を<b>全置換</b>で受け取り、サーバーが HOME/AWAY ごとに符号付き集計して
 * {@code matches.home_score}/{@code away_score}（整数スケール×1000）へ再導出反映する（二層正本・§4B.2）。
 * 勝敗の正準は再導出後のスコア列大小（§B.1.2）。合計点はサーバーが導出するため本 DTO には含めない
 * （クライアントの合計主張を信頼しない・マスアサインメント防止）。</p>
 *
 * <p><b>Schema 命名</b>: tournament ドメインの {@code Match*} と OpenAPI スキーマ名が衝突しないよう
 * {@code MatchRecordScoredComponentRequest} を明示する（feedback_spring_bean_name_collision_same_simplename と同趣旨）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B / 01 §B.1.2 / §D.8</p>
 */
@Schema(name = "MatchRecordScoredComponentRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchScoredComponentRequest {

    /** 採点内訳明細の一覧（全置換・1〜200 件）。 */
    @NotEmpty
    @Size(max = 200)
    @Valid
    private List<Line> components;

    /**
     * 採点内訳の 1 明細。
     *
     * <p>減点は {@code componentType=DEDUCTION}＋正の {@code pointsScaled}（絶対値）で表し、サーバーが
     * 集計時に符号付きで減算する。{@code componentType}/{@code apparatus} の競技別カタログ整合はサーバーが検証する。</p>
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "MatchRecordScoredComponentLine")
    public static class Line {

        /** 対戦 side（HOME/AWAY・2 者対戦 MVP・必須）。 */
        @Schema(description = "対戦 side（HOME/AWAY）", example = "HOME")
        @NotNull
        private TeamSide competitorSide;

        /** 種目/セグメント（体操の FLOOR… フィギュアの SP/FS・任意・指定時は競技別カタログ列挙）。 */
        @Schema(description = "種目/セグメント（体操の FLOOR/POMMEL_HORSE… フィギュアの SP/FS・任意）", example = "SP")
        private ScoredApparatus apparatus;

        /** 審判識別（J1〜J9 等・任意）。 */
        @Schema(description = "審判識別（J1〜J9 等・任意）", example = "J1")
        @Size(max = 32)
        private String judgeLabel;

        /** 項目（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE・必須・競技別カタログ列挙）。 */
        @Schema(description = "項目（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE）", example = "TES")
        @NotNull
        private ScoredComponentType componentType;

        /** 当該項目の点数（整数スケール×1000・非負・減点も絶対値を正で入れる・例 88.43→88430）。 */
        @Schema(description = "当該項目の点数（整数スケール×1000・非負・例 88430）", example = "88430")
        @NotNull
        @Min(0)
        @Max(Integer.MAX_VALUE)
        private Integer pointsScaled;
    }
}
