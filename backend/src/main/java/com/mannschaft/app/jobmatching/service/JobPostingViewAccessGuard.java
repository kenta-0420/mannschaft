package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 求人の書込前に通す「閲覧可否の門」（CMP-260821-1027 存在オラクルの本文正規化）。
 *
 * <h2>保証する内容</h2>
 * <p>求人を知り得ない者（詳細取得 {@link JobPostingService#findById} と同じ F00 可視性基盤
 * {@link ContentVisibilityChecker} で見えず、かつ当該求人の編集・採否の権限も持たない者）には、
 * 不在の求人IDと同一の {@link JobmatchingErrorCode#JOB_NOT_FOUND}（404・同一本文）を返して実在を秘匿する。
 * 求人を知り得る者はそのまま通し、権限不足（{@code JOB_PERMISSION_DENIED}）や状態の診断
 * （{@code JOB_NOT_OPEN} 等）は従来どおり {@link JobPostingService} / {@link JobApplicationService} に委ねる
 * （見えている求人について診断を返しても、新たな存在情報は漏れない）。</p>
 *
 * <p>編集権限者・採否権限者を「知り得る者」に含めるのは、DRAFT 求人は F00 上で投稿者本人と SYSTEM_ADMIN にしか
 * 見えない一方、チーム ADMIN（編集権限）や {@code jobs.manage} 保有者（採否権限）は従来から操作できるためである。
 * 門で落とすと正当な操作まで不在扱いになる。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</h2>
 * <p>{@link JobPostingService} 等はクラス単位で {@code @Transactional} のため、その中から可視性判定
 * （membership / role ドメインの Repository）へ到達すると、取引が jobmatching の外へ越境する
 * （D-3T 番人、CLAUDE.md DB 設計の原則 #5）。本部品は取引を持たず、他ドメインの判定はそれぞれの取引で行う
 * （{@code BlogScopeAccessGuard}・{@code CorkboardScopedBoardVisibilityGuard} と同じ型）。</p>
 */
@Component
@RequiredArgsConstructor
public class JobPostingViewAccessGuard {

    private final JobPostingRepository postingRepository;
    private final ContentVisibilityChecker visibilityChecker;
    private final JobPolicy jobPolicy;

    /**
     * 呼出者が求人を知り得ることを確かめる。
     *
     * @param postingId 求人ID（パス由来）
     * @param userId    操作ユーザーID
     * @throws BusinessException 不在・知り得ない者（{@link JobmatchingErrorCode#JOB_NOT_FOUND} / 404）
     */
    public void requireVisible(Long postingId, Long userId) {
        JobPostingEntity posting = postingRepository.findById(postingId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_NOT_FOUND));
        if (visibilityChecker.canView(ReferenceType.JOB_POSTING, postingId, userId)
                || jobPolicy.canEditPosting(userId, posting)
                || jobPolicy.canDecideApplication(userId, posting)) {
            return;
        }
        throw new BusinessException(JobmatchingErrorCode.JOB_NOT_FOUND);
    }
}
