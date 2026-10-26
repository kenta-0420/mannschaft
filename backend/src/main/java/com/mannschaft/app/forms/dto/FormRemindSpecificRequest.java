package com.mannschaft.app.forms.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 特定者向けフォームリマインドリクエスト DTO（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code POST /api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/remind-specific} のリクエスト。
 * 全未提出者宛の {@code .../remind} とは別経路で、ADMIN が特定ユーザーにのみ送信する。</p>
 */
@Data
public class FormRemindSpecificRequest {

    /** リマインド対象のユーザーIDリスト */
    @NotEmpty
    private List<Long> userIds;

    /** 補足メッセージ（任意） */
    private String message;
}
