package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.timeline.PostScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * タイムライン添付ファイルの Presigned アップロード URL 発行に対する認可ゲート（認可根治戦役 Wave7）。
 *
 * <p>アップロード先スコープ（{@code scopeType}/{@code scopeId}）はリクエストボディ由来のため、
 * 投稿作成（{@link TimelinePostService#checkScopeMembership}）と同じ粒度で呼び出し元のスコープ
 * メンバーシップを検証してから Presigned URL を発行する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineAttachmentAccessGuard {

    private final AccessControlService accessControlService;

    /**
     * 添付ファイルのアップロード先スコープへの書き込み権限を検証する。
     *
     * <ul>
     *   <li>PUBLIC: 検証なし（誰でも投稿できる公開スコープ）</li>
     *   <li>PERSONAL: 検証なし。{@code TimelineImageAttachmentService#resolveScope} /
     *       {@code TimelineVideoAttachmentService#resolveScope} は PERSONAL では
     *       リクエストの {@code scopeId} を無視し、クォータ計上は常に<b>呼び出し元自身</b>
     *       （{@code userId}）に帰属する。R2 オブジェクトキーのパス文字列に他人の ID 風の値が
     *       混じっても、キー自体は乱数 UUID サフィックス付きの書き込み専用宛先であり、
     *       他人のデータへの読取/書込アクセスにはつながらない</li>
     *   <li>TEAM / ORGANIZATION: {@code scopeId} がそのままクォータ計上・R2 キーの両方に使われるため、
     *       呼び出し元がそのスコープのメンバーであることを検証する
     *       （{@link AccessControlService#checkMembership}）</li>
     *   <li>その他（VILLAGE / FRIEND_*）: 本 API の正路ではないため fail-closed</li>
     * </ul>
     *
     * @param userId       操作ユーザー ID
     * @param scopeTypeStr スコープ種別文字列（大文字・小文字は呼び出し元で正規化済みであること）
     * @param scopeId      スコープ ID（TEAM/ORGANIZATION のみ意味を持つ）
     * @throws BusinessException 権限がない場合（{@link CommonErrorCode#COMMON_002}）
     */
    public void checkCanUpload(Long userId, String scopeTypeStr, Long scopeId) {
        PostScopeType scopeType;
        try {
            scopeType = PostScopeType.valueOf(scopeTypeStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        switch (scopeType) {
            case PUBLIC, PERSONAL -> {
                // 実スコープ解決側が scopeId を無視して呼び出し元本人へ固定するため検証不要。
            }
            case TEAM -> accessControlService.checkMembership(userId, scopeId, "TEAM");
            case ORGANIZATION -> accessControlService.checkMembership(userId, scopeId, "ORGANIZATION");
            case VILLAGE, FRIEND_TEAM, FRIEND_FORWARD, FRIEND_ARCHIVE ->
                    throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
