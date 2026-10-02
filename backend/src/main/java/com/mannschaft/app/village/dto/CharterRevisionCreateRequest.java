package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 「改正を確定」リクエスト（{@code POST .../charter/revisions}・F17.3・設計書 §18.2）。
 *
 * <p>{@code last_revised_at} を更新し、改定履歴に 1 行追記する（§8.2）。{@code note} は任意
 * （≤200・DDL VARCHAR(200) と一致）。この操作は改定日・履歴を刻む里程標であって、条文の可視性は
 * 変えない（保存＝即時公開・下書き状態は存在しない・§8.2/AC-17b）。</p>
 */
public record CharterRevisionCreateRequest(

        @Schema(description = "改定メモ（任意・最大200字）")
        @Size(max = 200) String note
) {
}
