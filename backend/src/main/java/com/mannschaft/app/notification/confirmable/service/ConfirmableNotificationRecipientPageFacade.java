package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientPageResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 受信者一覧ページング（recipients/page）の認可ファサード（CMP-260923-0954 W3b・plan4 の型）。
 *
 * <p><b>{@code @Transactional} を付けない</b>（クラスにもメソッドにも）。認可（スコープ所属・ADMIN 判定）を
 * トランザクションの外で済ませ、tx 本体 {@link ConfirmableNotificationQueryService#getRecipientsPage} は
 * {@code AccessControlService} / Gate に依存しない。tx 内に認可を置くと、D-3T 番人が common の認可経由の
 * 他ドメイン到達を数えてしまうため。</p>
 *
 * <p>順序: 通知の実体からスコープを解く（readOnly）→ 認可 → tx 本体（中で通知を読み直す。
 * 認可の後に通知が消えていれば不在 ID と同一の {@code NOT_FOUND}）。通知のスコープはパスのスコープと
 * 一致しなければ不在扱い（他スコープの実在 ID を不在と別応答にしない）。通知のスコープ列は不変。</p>
 */
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationRecipientPageFacade {

    private final ConfirmableNotificationQueryService queryService;
    private final ConfirmableScopeAuthorizer scopeAuthorizer;
    private final AccessControlService accessControlService;

    /**
     * 受信者一覧をページングして取得する。
     *
     * @param scopeType       パスのスコープ種別
     * @param scopeId         パスのスコープID
     * @param notificationId  確認通知ID
     * @param requesterUserId リクエスト元ユーザーID
     * @param page            ページ番号（0始まり）
     * @param size            ページサイズ
     * @param unconfirmedOnly 未確認者のみに絞り込むか
     * @throws BusinessException 通知が不在・他スコープなら NOT_FOUND、関係者の権限不足は COMMON_002
     */
    public ConfirmableNotificationRecipientPageResponse getRecipientsPage(
            ScopeType scopeType, Long scopeId, Long notificationId, Long requesterUserId,
            int page, int size, boolean unconfirmedOnly) {
        ConfirmableNotificationEntity notification = queryService.getDetail(notificationId);
        if (scopeType != notification.getScopeType() || !scopeId.equals(notification.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND);
        }
        // 閲覧自体は在籍が条件。越境は不在 ID と同一応答、関係者の権限不足は 403。
        scopeAuthorizer.requireMember(requesterUserId, scopeType, scopeId, ConfirmableNotificationErrorCode.NOT_FOUND);
        // ADMIN / CREATOR / MEMBER の視点は tx 本体が決める。ADMIN 判定だけここで済ませて渡す。
        boolean requesterIsAdmin = accessControlService.isAdminOrAbove(requesterUserId, scopeId, scopeType.name());
        return queryService.getRecipientsPage(
                notificationId, requesterUserId, requesterIsAdmin, page, size, unconfirmedOnly);
    }
}
