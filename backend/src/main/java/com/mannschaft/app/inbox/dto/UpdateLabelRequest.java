package com.mannschaft.app.inbox.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.11 統合通知インボックス：ラベル更新リクエスト DTO。
 *
 * <p>設計書: 02_api_design.md §3.4。全フィールド任意（null は「変更しない」を意味する）。
 * {@code name} を変更する場合のみ現役同名重複検証を行う（サービス層）。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateLabelRequest {

    /** ラベル名（任意・最大 50。null/空白なら変更しない） */
    @Size(max = 50)
    private final String name;

    /** 表示色 #RRGGBB（任意・最大 7） */
    @Size(max = 7)
    private final String color;

    /** PrimeIcons 名（任意・最大 40） */
    @Size(max = 40)
    private final String icon;

    /** 表示順（任意。null なら変更しない） */
    private final Integer sortOrder;
}
