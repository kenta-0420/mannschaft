package com.mannschaft.app.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 団体戦の子ボード作成リクエスト（将棋/囲碁・01 §B.6 / sports/05_shogi.md §8.1）。
 *
 * <p>親（団体戦）match 配下にボードを起こす。テナント・主体チーム・競技・記録モードは親から継承する
 * （サーバー導出・クライアント値を信頼しない）。本 DTO は {@code boardNumber}（順序）と相手（任意）のみ受け取る。</p>
 *
 * <p><b>Schema 命名</b>: tournament ドメインとの衝突を避けて {@code MatchRecordBoardCreateRequest} を明示する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.6 / sports/05_shogi.md §8.1</p>
 */
@Schema(name = "MatchRecordBoardCreateRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchBoardCreateRequest {

    /** ボード順（1=大将/主将 等・親内一意）。 */
    @NotNull
    @Min(1)
    @Max(99)
    private Integer boardNumber;

    /** 相手チーム ID（任意・親から継承する場合は null）。 */
    private Long opponentTeamId;

    /** 未登録相手名（任意）。 */
    @Size(max = 128)
    private String opponentName;
}
