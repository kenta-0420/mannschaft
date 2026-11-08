package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.MatchingErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 応募（match_proposals）の書込前に通す「閲覧可否の門」（CMP-260821-1027 存在オラクルの本文正規化）。
 *
 * <h2>保証する内容</h2>
 * <p>応募を閲覧できない者には、不在の応募IDと同一の {@link MatchingErrorCode#PROPOSAL_NOT_FOUND}
 * （MATCHING_002・404・同一本文）を返して実在を秘匿する。閲覧資格は、キャンセル・合意承認の経路が既に使っている
 * {@link MatchProposalService#assertProposalReadable}（応募チームの所属者・管理者、親募集が残っていれば
 * 募集チームの所属者・管理者、SYSTEM_ADMIN）と同一である。閲覧できる者はそのまま通し、権限不足（MATCHING_010）・
 * 親募集の不在（MATCHING_001）・状態（MATCHING_005 等）の診断は従来どおり {@link MatchProposalService} に委ねる。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</h2>
 * <p>承諾は募集行を悲観ロック（{@code SELECT ... FOR UPDATE}）で取ってから判定するため、TX の中で閲覧判定を
 * 行うとロックの待ちと D-3T（membership への越境）が生じる。本部品は TX とロックの外で先に判定する。</p>
 */
@Component
@RequiredArgsConstructor
public class MatchProposalViewAccessGuard {

    private final MatchProposalService proposalService;

    /**
     * 呼出者が応募を閲覧できることを確かめる。
     *
     * @param proposalId 応募ID（パス由来）
     * @param userId     操作ユーザーID
     * @throws BusinessException 不在・閲覧できない者（{@link MatchingErrorCode#PROPOSAL_NOT_FOUND} / 404）
     */
    public void requireVisible(Long proposalId, Long userId) {
        proposalService.assertProposalReadable(proposalId, userId);
    }
}
