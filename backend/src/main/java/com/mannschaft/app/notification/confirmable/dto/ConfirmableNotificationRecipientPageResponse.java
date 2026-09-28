package com.mannschaft.app.notification.confirmable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CMP-260920-1040 F04.9 受信者一覧のページング応答契約（軍議第8版確定稿 §9.5）。
 *
 * <p>件数と閲覧者の種別は BE が明示的に返す。FE がページの中身から推測しないようにするため、
 * {@code ConfirmableNotificationRecipients.vue} の件数表示・閲覧者種別の推定ロジックは撤去する
 * （§9.5「FE 隊の担当」）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationRecipientPageResponse {

    private List<ConfirmableNotificationRecipientResponse> items;

    private int page;

    private int size;

    /** 通知全体の受信者総数（ページの中身によらず一定。AC-59） */
    private long totalElements;

    /** 通知全体の確認済み件数（AC-59） */
    private long confirmedCount;

    /** 通知全体の未確認件数（AC-59） */
    private long unconfirmedCount;

    /** 閲覧者の種別。ADMIN / CREATOR / MEMBER（AC-59・AC-60） */
    private ViewerRole viewerRole;

    /**
     * 閲覧者種別（§9.5）。
     */
    public enum ViewerRole {
        ADMIN,
        CREATOR,
        MEMBER
    }
}
