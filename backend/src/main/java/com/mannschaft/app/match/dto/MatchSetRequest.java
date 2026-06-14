package com.mannschaft.app.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * セットスコア記録/更新リクエスト（バレーボール・sports/04_volleyball.md §8.1 主動線・01 §B.5）。
 *
 * <p>{@code setNumber}（1〜5）をキーに upsert する。得点はラリーポイント（通常 25 点/最終 15 点・デュース）。
 * <b>セット勝者・最終セット判定・獲得セット数集計はサーバー側が導出</b>するため本 DTO には含めない
 * （クライアントの勝敗主張を信頼しない・マスアサインメント防止）。</p>
 *
 * <p><b>Schema 命名</b>: tournament ドメインに既存の {@code MatchSetRequest} があるため、
 * OpenAPI スキーマ名衝突を避けて {@code MatchRecordSetRequest} を明示する
 * （feedback_spring_bean_name_collision_same_simplename と同趣旨の OpenAPI 版回避）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/04_volleyball.md §4 / §8.1 / 01 §B.5</p>
 */
@Schema(name = "MatchRecordSetRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchSetRequest {

    /** セット番号（1〜5・best-of-5）。 */
    @NotNull
    @Min(1)
    @Max(5)
    private Integer setNumber;

    /** 当該セットのホーム得点（0〜99・デュースで延長しても現実的上限）。 */
    @NotNull
    @Min(0)
    @Max(99)
    private Integer homePoints;

    /** 当該セットのアウェイ得点。 */
    @NotNull
    @Min(0)
    @Max(99)
    private Integer awayPoints;
}
