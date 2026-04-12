package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F04.9 確認通知テンプレート作成リクエストDTO。
 */
@Getter
@NoArgsConstructor
public class ConfirmableNotificationTemplateCreateRequest {

    /** 管理用テンプレート名（必須） */
    @NotBlank
    private String name;

    /** テンプレートタイトル（必須） */
    @NotBlank
    private String title;

    /** テンプレート本文（任意） */
    private String body;

    /** デフォルト優先度（省略時は NORMAL） */
    private ConfirmableNotificationPriority defaultPriority;
}
