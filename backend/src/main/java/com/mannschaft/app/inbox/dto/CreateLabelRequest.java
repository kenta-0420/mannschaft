package com.mannschaft.app.inbox.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.11 統合通知インボックス：ラベル作成リクエスト DTO。
 *
 * <p>設計書: 02_api_design.md §3.4。{@code name} は必須・最大 50 文字。
 * {@code color}（#RRGGBB）・{@code icon}（PrimeIcons 名）は任意。
 * 形式検証（色・アイコンプレフィックス）はサービス層で行う（04_security_operations.md §2）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateLabelRequest {

    /** ラベル名（必須・最大 50） */
    @NotBlank
    @Size(max = 50)
    private final String name;

    /** 表示色 #RRGGBB（任意・最大 7） */
    @Size(max = 7)
    private final String color;

    /** PrimeIcons 名（任意・最大 40） */
    @Size(max = 40)
    private final String icon;
}
