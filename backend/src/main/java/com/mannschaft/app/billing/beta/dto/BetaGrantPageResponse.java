package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.3 ベータ特典: シスアド 付与一覧のページング結果（設計書 02 §4）。
 *
 * <p>F20.1 {@code BillingPagedContractResponse} と同型（content / page / size / totalElements）。</p>
 */
@Getter
@Builder
@Schema(name = "BetaPerkGrantPageResponse", description = "F20.3 シスアド ベータ特典 付与一覧（ページング）")
public class BetaGrantPageResponse {

    @Schema(description = "付与一覧（当該ページ）")
    private final List<BetaGrantDetailResponse> content;

    @Schema(description = "現在ページ番号（0 始まり）", example = "0")
    private final int page;

    @Schema(description = "ページサイズ", example = "20")
    private final int size;

    @Schema(description = "総件数", example = "42")
    private final long totalElements;
}
