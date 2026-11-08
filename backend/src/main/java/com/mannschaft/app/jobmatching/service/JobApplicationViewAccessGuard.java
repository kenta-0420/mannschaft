package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 求人応募の書込前に通す「閲覧可否の門」（CMP-260821-1027 存在オラクルの本文正規化）。
 *
 * <h2>保証する内容</h2>
 * <p>応募を閲覧できない者には、不在の応募IDと同一の {@link JobmatchingErrorCode#JOB_APPLICATION_NOT_FOUND}
 * （404・同一本文）を返して実在を秘匿する。閲覧資格は応募詳細（{@link JobApplicationService#findById}）と同じ
 * 「応募者本人、または対象求人の採否権限者（{@link JobPolicy#canDecideApplication}）」である。
 * 閲覧できる者はそのまま通し、その操作の権限不足（応募者本人の採否、採否権限者の取り下げ等）は従来どおり
 * Service 側の {@code JOB_PERMISSION_DENIED} で返す。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</h2>
 * <p>採否権限の判定は membership / role ドメインの Repository へ到達するため、書込 TX の中で行うと D-3T
 * （取引の越境）に当たる。また採用確定（{@link JobContractService#acceptApplication}）は判定の前に求人単位の
 * {@code GET_LOCK} を取るため、TX の中で判定するとロック競合時の {@code JOB_INVALID_STATE_TRANSITION}（409）が
 * 閲覧できない者にも返り、不在（404）と割れる。本部品はロックと TX の外で先に判定する。</p>
 */
@Component
@RequiredArgsConstructor
public class JobApplicationViewAccessGuard {

    private final JobApplicationRepository applicationRepository;
    private final JobPostingRepository postingRepository;
    private final JobPolicy jobPolicy;

    /**
     * 呼出者が応募を閲覧できることを確かめる。
     *
     * @param applicationId 応募ID（パス由来）
     * @param userId        操作ユーザーID
     * @throws BusinessException 不在・閲覧できない者（{@link JobmatchingErrorCode#JOB_APPLICATION_NOT_FOUND} / 404）
     */
    public void requireVisible(Long applicationId, Long userId) {
        JobApplicationEntity app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_APPLICATION_NOT_FOUND));
        if (userId != null && userId.equals(app.getApplicantUserId())) {
            return;
        }
        boolean decider = postingRepository.findById(app.getJobPostingId())
                .map(posting -> jobPolicy.canDecideApplication(userId, posting))
                .orElse(false);
        if (!decider) {
            throw new BusinessException(JobmatchingErrorCode.JOB_APPLICATION_NOT_FOUND);
        }
    }
}
