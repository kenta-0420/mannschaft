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

    /**
     * CMP-260920-1040: 既定の宛先グループID（軍議第8版確定稿 §3.1・AC-32）。
     *
     * <p>参照先グループが論理削除済みの場合はNULLを返す（「既定＝配下すべて」に戻す。
     * 骨格段階のMapStruct既定マッピングはこの判定を行わないため未実装。出陣で実装する）。</p>
     */
    private java.util.UUID defaultRecipientGroupId;

    private LocalDateTime createdAt;
}
