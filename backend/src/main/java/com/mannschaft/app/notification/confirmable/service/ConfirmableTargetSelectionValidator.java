package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先入力の意味を解決する（軍議第8版確定稿 §3.3・AC-8〜11）。
 *
 * <ul>
 *   <li>targets を省略した場合と null の場合 → 既定の宛先（AC-8）</li>
 *   <li>targets=[] → {@code TARGETS_EMPTY}（400・AC-9）。何も作らない</li>
 *   <li>targets と recipientGroupId を両方指定 → {@code TARGETS_AND_GROUP_BOTH_SPECIFIED}（400・AC-10）</li>
 *   <li>{@code recipientUserIds} は公開 API では無視する（AC-11）</li>
 * </ul>
 */
@Component
public class ConfirmableTargetSelectionValidator {

    /**
     * 宛先の入力の意味を解決した結果。
     *
     * <p>{@code targets} と {@code recipientGroupId} のどちらも {@code null} なら「既定の宛先」を意味する
     * （AC-1 / AC-6。呼び出し側が送信スコープの自組織・自チームへ展開する）。</p>
     */
    public record Resolution(List<ConfirmableTargetSpec> targets, UUID recipientGroupId) {

        public boolean isDefault() {
            return targets == null && recipientGroupId == null;
        }

        public boolean isGroup() {
            return recipientGroupId != null;
        }
    }

    /**
     * 宛先の入力を解決する。{@code recipientUserIdsIgnored} は AC-11 のとおり読み捨てる（公開 API では
     * 廃止されたフィールドであり、指定されても受信者には反映しない）。
     */
    public Resolution resolve(List<ConfirmableTargetSpec> targets, UUID recipientGroupId,
            List<Long> recipientUserIdsIgnored) {
        if (targets != null && recipientGroupId != null) {
            throw new BusinessException(ConfirmableNotificationErrorCode.TARGETS_AND_GROUP_BOTH_SPECIFIED);
        }
        if (targets != null && targets.isEmpty()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.TARGETS_EMPTY);
        }
        return new Resolution(targets, recipientGroupId);
    }
}
