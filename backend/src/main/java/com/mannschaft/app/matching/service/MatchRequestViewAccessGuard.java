package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 募集（match_requests）の書込前に通す「閲覧可否の門」（CMP-260821-1027 存在オラクルの本文正規化）。
 *
 * <h2>保証する内容</h2>
 * <p>募集を閲覧できない者（詳細取得と同じ {@link MatchRequestVisibilityPolicy} で見えない者）には、
 * 不在の募集IDと同一の {@link MatchingErrorCode#REQUEST_NOT_FOUND}（MATCHING_001・404・同一本文）を返して
 * 実在を秘匿する。閲覧できる者はそのまま通し、権限不足（MATCHING_010）・状態（MATCHING_006 等）・
 * NG（MATCHING_007）の診断は従来どおり {@link MatchRequestService} / {@link MatchProposalService} に委ねる。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</h2>
 * <p>閲覧判定は membership / role ドメインの Repository へ到達するため、書込 TX の中で行うと D-3T
 * （取引の越境）に当たる。本部品は取引を持たず、他ドメインの判定はそれぞれの取引で行う
 * （{@code CorkboardScopedBoardVisibilityGuard} と同じ型）。</p>
 */
@Component
@RequiredArgsConstructor
public class MatchRequestViewAccessGuard {

    private final MatchRequestRepository requestRepository;
    private final MatchRequestVisibilityPolicy visibilityPolicy;

    /**
     * 呼出者が募集を閲覧できることを確かめる。
     *
     * @param requestId 募集ID（パス由来）
     * @param userId    操作ユーザーID
     * @throws BusinessException 不在・閲覧できない者（{@link MatchingErrorCode#REQUEST_NOT_FOUND} / 404）
     */
    public void requireVisible(Long requestId, Long userId) {
        MatchRequestEntity entity = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));
        if (!visibilityPolicy.isVisible(entity, userId)) {
            throw new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND);
        }
    }
}
