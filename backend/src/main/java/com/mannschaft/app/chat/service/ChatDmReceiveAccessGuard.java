package com.mannschaft.app.chat.service;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.common.BusinessException;

import java.util.function.BooleanSupplier;

/**
 * 会話（Kabine / Zimmer）の相手側が、呼出ユーザーからの DM を受け取る設定であるかの判定（相手側の受信設定）。
 *
 * <p>相手のブロック設定と DM 受信範囲設定（{@link DmReceiveFrom}）の双方を検証する。
 * 1 対 1 の Kabine とグループの Zimmer、Kabine からの招待、チャンネル作成が<b>同一の判定</b>を通ることで、
 * 参加人数や経路によって相手の受信設定が無視される非対称を作らない。</p>
 *
 * <p><b>{@link ChatChannelAccessGuard} と分けている理由（CMP-261105-1507）</b>: チャンネルのガードは
 * 「既存チャンネルを操作してよいか」をトランザクションの外（Facade）で判定する。これに対し本ガードは
 * 「この相手へ会話を作ってよいか」（相手の受信設定）を判定し、判定に要る相手ユーザー・ブロック・所属・連絡先は
 * 会話の作成と同じトランザクションで {@link ChatChannelService} が読む（会話開始・チャンネル作成・Zimmer 招待）。
 * 本クラスは Spring Bean ではなく状態を持たない判定だけを担い、入力を真偽値・列挙で受け取る
 * （chat ドメインが auth / user / role / dashboard のエンティティを直接参照しないため）。
 * 受信範囲設定に応じてしか要らない 2 つの照会は {@link BooleanSupplier} で受け、不要な問い合わせを走らせない。</p>
 */
final class ChatDmReceiveAccessGuard {

    private ChatDmReceiveAccessGuard() {
    }

    /**
     * 相手が呼出ユーザーからの DM を受け取れることを保証する。
     *
     * @param blockedByReceiver   相手が呼出ユーザーをブロックしているか
     * @param receiverSetting     相手の DM 受信範囲設定
     * @param sharesTeam          相手と共通チームに所属しているか（{@code TEAM_MEMBERS_ONLY} でのみ評価）
     * @param registeredAsContact 相手の連絡先に登録されているか（{@code CONTACTS_ONLY} でのみ評価）
     * @throws BusinessException ブロックされている場合（{@link ChatErrorCode#CHANNEL_ACCESS_DENIED}）/
     *                           受信範囲外の場合（{@link ChatErrorCode#DM_RECEIVE_RESTRICTED}）
     */
    static void requireDeliverable(boolean blockedByReceiver,
                                   DmReceiveFrom receiverSetting,
                                   BooleanSupplier sharesTeam,
                                   BooleanSupplier registeredAsContact) {
        if (blockedByReceiver) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        if (receiverSetting == DmReceiveFrom.TEAM_MEMBERS_ONLY) {
            if (!sharesTeam.getAsBoolean()) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        } else if (receiverSetting == DmReceiveFrom.CONTACTS_ONLY) {
            if (!registeredAsContact.getAsBoolean()) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        }
    }
}
