package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.1: シスアド 契約横断検索の結果（ページング・設計書 02 §4）。
 */
@Getter
@Builder
@Schema(name = "BillingPagedContractResponse", description = "F20.1 シスアド 契約横断検索結果")
public class PagedContractResponse {

    @Schema(description = "契約一覧（当該ページ）")
    private final List<ContractResponse> content;

    @Schema(description = "現在ページ番号（0 始まり）", example = "0")
    private final int page;

    @Schema(description = "ページサイズ", example = "20")
    private final int size;

    @Schema(description = "総件数", example = "42")
    private final long totalElements;
}
