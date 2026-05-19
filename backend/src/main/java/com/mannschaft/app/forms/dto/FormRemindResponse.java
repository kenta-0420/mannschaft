package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォームリマインド送信レスポンス DTO（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>未提出者リマインド（{@code POST .../remind}）および
 * 特定者向けリマインド（{@code POST .../remind-specific}）の共通レスポンス。</p>
 */
@Getter
@RequiredArgsConstructor
public class FormRemindResponse {

    /** 実際にリマインドした対象数 */
    private final int remindedCount;

    /** リマインド対象の総数（未提出者数）。{@code remind-specific} の場合はリクエスト指定の総数 */
    private final int totalTargets;
}
