package com.mannschaft.app.todo.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;

/**
 * TODOステータス変更リクエストDTO。
 *
 * <p>F02.3.1 でカスタムステータスラベル対応のため、{@code status} と
 * {@code statusLabelId} のいずれか一方以上を必須とする（両方指定可・整合確認は Service 層）。</p>
 */
@Getter
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
     * Jackson デシリアライズ + 通常呼び出し兼用の正規コンストラクタ。
     */
    @JsonCreator
    public TodoStatusChangeRequest(
            @JsonProperty("status") String status,
            @JsonProperty("statusLabelId") Long statusLabelId) {
        this.status = status;
        this.statusLabelId = statusLabelId;
    }

    /**
     * status のみで作成する後方互換コンストラクタ（F02.3.1 以前の呼び出し元向け）。
     *
     * @param status ステータス文字列
     */
    public TodoStatusChangeRequest(String status) {
        this(status, null);
    }

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
