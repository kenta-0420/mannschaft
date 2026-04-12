package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知テンプレートレスポンスDTO。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationTemplateResponse {

    private Long id;
    private ScopeType scopeType;
    private Long scopeId;

    /** 管理用テンプレート名 */
    private String name;

    /** テンプレートタイトル */
    private String title;

    /** テンプレート本文（任意） */
    private String body;

    /** デフォルト優先度 */
    private ConfirmableNotificationPriority defaultPriority;

    private LocalDateTime createdAt;
}
