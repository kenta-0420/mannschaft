package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * F04.11 統合通知インボックスの認可ゲート。認可根治戦役 第1波・個人領域。
 *
 * <h2>保証する内容</h2>
 * <ul>
 *   <li><b>ラベル</b>: 参照・更新・削除・付与解除の対象ラベルは<b>操作者本人が所有するもの</b>に限る。
 *       認可スコープはリクエストではなく実体の {@code user_id} 由来で確定する
 *       （{@code findByIdAndUserId} で id と所有者を同時に条件化する）。他者所有・不存在・
 *       論理削除済みはいずれも {@link InboxErrorCode#INBOX_LABEL_NOT_FOUND}（404）に正規化して
 *       <b>存在を秘匿</b>する。</li>
 *   <li><b>triage / ラベル付与の対象通知</b>: 一覧取得と同一のソースアダプタ判定
 *       （{@link InboxItemVisibilityChecker}）で<b>本人に可視な通知のみ</b>を対象とする。
 *       可視でない対象は {@link InboxErrorCode#INBOX_SOURCE_NOT_FOUND}（404）。
 *       これにより他人宛て通知へオーバーレイ行・ラベルリンクを作る経路を塞ぐ。</li>
 * </ul>
 *
 * <h2>本クラスに集約した理由</h2>
 * <p>ラベル所有判定は {@link InboxLabelService} の private ヘルパ、可視性判定は
 * {@link InboxTriageService} / {@link InboxLabelService} の各所に散っていた。認可の所在を 1 箇所へ
 * 集約し、いずれかの経路だけ判定が抜ける事故を構造的に防ぐ。</p>
 */
@Component
@RequiredArgsConstructor
public class InboxAccessGuard {

    private final NotificationLabelRepository labelRepository;
    private final InboxItemVisibilityChecker visibilityChecker;

    /**
     * 本人所有のラベルを取得する。
     *
     * @param userId  操作ユーザー ID（認証主体）
     * @param labelId 対象ラベル ID
     * @return 本人所有のラベル実体
     * @throws BusinessException 他者所有・不存在・論理削除済み（INBOX_LABEL_NOT_FOUND / 404）
     */
    public NotificationLabelEntity requireOwnedLabel(Long userId, UUID labelId) {
        return labelRepository.findByIdAndUserId(labelId, userId)
                .orElseThrow(() -> new BusinessException(InboxErrorCode.INBOX_LABEL_NOT_FOUND));
    }

    /**
     * 対象通知が本人に可視かを判定する（例外を投げない版）。
     *
     * <p>既存オーバーレイ行の有無で再検証を省く判断（{@link InboxTriageService}）に用いる。</p>
     *
     * @param userId     操作ユーザー ID（認証主体）
     * @param sourceType 通知ソース種別
     * @param sourceId   各ソース PK
     * @return 本人に可視なら true。担当アダプタ未実装の種別は fail-closed で false
     */
    public boolean isSourceVisible(Long userId, InboxSourceType sourceType, Long sourceId) {
        return visibilityChecker.isVisibleTo(userId, sourceType, sourceId);
    }

    /**
     * 対象通知が本人に可視であることを要求する（書き込み前検証）。
     *
     * @param userId     操作ユーザー ID（認証主体）
     * @param sourceType 通知ソース種別
     * @param sourceId   各ソース PK
     * @throws BusinessException 本人に可視でない場合（INBOX_SOURCE_NOT_FOUND / 404）
     */
    public void requireVisibleSource(Long userId, InboxSourceType sourceType, Long sourceId) {
        if (!isSourceVisible(userId, sourceType, sourceId)) {
            throw new BusinessException(InboxErrorCode.INBOX_SOURCE_NOT_FOUND);
        }
    }
}
