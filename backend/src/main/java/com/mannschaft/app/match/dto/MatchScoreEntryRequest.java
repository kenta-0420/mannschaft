package com.mannschaft.app.match.dto;

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
 * 採点競技（フィギュアスケート/体操）の<b>多人数順位制の出場者エントリ</b>記録リクエスト
 * （sports/07_scored.md §5B / 01 §B.1.2 / §D.8）。
 *
 * <p>出場者エントリの一覧を<b>全置換</b>で受け取り、サーバーが合計点降順で順位（{@code rank_position}）を
 * 算出して保存する（同点同順位 1,2,2,4・§5B.2 / §6）。順位はサーバーが導出するため本 DTO には含めない
 * （クライアントの順位主張を信頼しない・マスアサインメント防止）。補助として最上位エントリの合計点を
 * {@code matches.home_score} に再導出反映する（二層正本・§5B.2）。</p>
 *
 * <p><b>Schema 命名</b>: tournament ドメインの {@code Match*} と OpenAPI スキーマ名が衝突しないよう
 * {@code MatchRecordScoreEntryRequest} を明示する（feedback_spring_bean_name_collision_same_simplename と同趣旨）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §5B / 01 §B.1.2 / §D.8</p>
 */
@Schema(name = "MatchRecordScoreEntryRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchScoreEntryRequest {

    /** 出場者エントリ明細の一覧（全置換・1〜500 件）。 */
    @NotEmpty
    @Size(max = 500)
    @Valid
    private List<Line> entries;

    /**
     * 出場者エントリの 1 明細。
     *
     * <p>出場者は {@code competitorUserId}（登録選手）／{@code competitorTeamId}（団体採点）／
     * {@code competitorName}（未登録選手名）のいずれかで識別する（サーバーが「いずれか必須」を検証）。
     * 順位（{@code rankPosition}）はサーバーが合計点降順で算出するため本明細には含めない。</p>
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "MatchRecordScoreEntryLine")
    public static class Line {

        /** 出場選手（user ドメイン ID・登録選手・任意）。 */
        @Schema(description = "出場選手ユーザー ID（登録選手・任意）", example = "1234")
        private Long competitorUserId;

        /** 未登録選手名（{@code competitorUserId} 未指定時の表示名・任意）。 */
        @Schema(description = "未登録選手名（competitorUserId 未指定時・任意）", example = "山田 花子")
        @Size(max = 128)
        private String competitorName;

        /** 所属チーム（team ドメイン ID・団体採点時・任意）。 */
        @Schema(description = "所属チーム ID（団体採点時・任意）", example = "100")
        private Long competitorTeamId;

        /** 合計点（整数スケール×1000・非負・例 198.45→198450）。 */
        @Schema(description = "合計点（整数スケール×1000・非負・例 198450）", example = "198450")
        @NotNull
        @Min(0)
        @Max(Integer.MAX_VALUE)
        private Integer totalScaled;
    }
}
