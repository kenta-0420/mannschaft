package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TODOステータス変更リクエストDTO。
 *
 * <p>F02.3.1 でカスタムステータスラベル対応のため、{@code status} と
 * {@code statusLabelId} のいずれか一方以上を必須とする（両方指定可・整合確認は Service 層）。</p>
 */
@Getter
@RequiredArgsConstructor
public class TodoStatusChangeRequest {

    /**
     * 直接指定するステータス（後方互換）。
     * NULL の場合は {@code statusLabelId} からバケット経由で導出する。
     */
    private final String status;

    /**
     * 指定するカスタムステータスラベル ID（F02.3.1）。
     * NULL の場合は {@code status} のみで動作（ラベル更新なし）。
     */
    private final Long statusLabelId;

    /**
     * status または statusLabelId のいずれか1つ以上が指定されていることを検証する。
     */
    @AssertTrue(message = "status または statusLabelId のいずれかを指定してください")
    public boolean isValidRequest() {
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasLabel = statusLabelId != null;
        return hasStatus || hasLabel;
    }
}
