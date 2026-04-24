package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.command.ApplyCommand;
import com.mannschaft.app.jobmatching.state.JobApplicationStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 求人応募サービス。F13.1 Phase 13.1.1 MVP。
 *
 * <p>応募の申込・取り下げ・不採用処理と一覧取得を担う。採用確定（ACCEPTED 遷移）および
 * それに伴う契約生成は {@link JobContractService#acceptApplication(Long, Long)} が担う。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class JobApplicationService {

    /** 自己PR 最大文字数。 */
    private static final int MAX_SELF_PR_LENGTH = 500;

    private final JobApplicationRepository applicationRepository;
    private final JobPostingRepository postingRepository;
    private final JobApplicationStateMachine stateMachine;
    private final JobPolicy jobPolicy;
    private final JobNotificationService notificationService;

    // ---------------------------------------------------------------------
    // コマンド系
    // ---------------------------------------------------------------------

    /**
     * 求人に応募する。
     *
     * <p>競合制御は軽量（SELECT → 検証 → INSERT）。厳密な定員制御は採用確定時
     * （{@link JobContractService#acceptApplication(Long, Long)}）で GET_LOCK + FOR UPDATE により行う。
     * ただし「求人 OPEN」「締切未通過」「自己応募禁止」「重複応募禁止」は応募時点で弾く。</p>
     *
     * @param postingId 応募対象の求人ID
     * @param cmd       応募コマンド
     * @param userId    応募者ユーザーID
     * @return 保存された応募
     */
    @Transactional
    public JobApplicationEntity apply(Long postingId, ApplyCommand cmd, Long userId) {
        Objects.requireNonNull(cmd, "cmd は必須");
        Objects.requireNonNull(userId, "userId は必須");

        JobPostingEntity posting = postingRepository.findById(postingId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_NOT_FOUND));

        if (posting.getStatus() != JobPostingStatus.OPEN) {
            throw new BusinessException(JobmatchingErrorCode.JOB_NOT_OPEN);
        }

        LocalDateTime now = LocalDateTime.now();
        if (posting.getApplicationDeadlineAt() != null
                && now.isAfter(posting.getApplicationDeadlineAt())) {
            throw new BusinessException(JobmatchingErrorCode.JOB_DEADLINE_PASSED);
        }

        if (userId.equals(posting.getCreatedByUserId())) {
            throw new BusinessException(JobmatchingErrorCode.JOB_CANNOT_APPLY_SELF);
        }

        // 早期の定員チェック（厳密判定は accept で GET_LOCK 保護下に再実行）。
        int accepted = applicationRepository.countByJobPostingIdAndStatus(
                postingId, JobApplicationStatus.ACCEPTED);
        if (accepted >= posting.getCapacity()) {
            throw new BusinessException(JobmatchingErrorCode.JOB_CAPACITY_FULL);
        }

        if (applicationRepository.findByJobPostingIdAndApplicantUserId(postingId, userId).isPresent()) {
            throw new BusinessException(JobmatchingErrorCode.JOB_ALREADY_APPLIED);
        }

        // visibility_scope に基づく応募権限（自分の求人への応募は上で除外済み）。
        if (!jobPolicy.canApply(userId, posting)) {
            if (!isSupportedScope(posting)) {
                throw new BusinessException(JobmatchingErrorCode.JOB_VIS_NOT_SUPPORTED);
            }
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        validateSelfPr(cmd.selfPr());

        JobApplicationEntity application = JobApplicationEntity.builder()
                .jobPostingId(postingId)
                .applicantUserId(userId)
                .selfPr(cmd.selfPr())
                .status(JobApplicationStatus.APPLIED)
                .appliedAt(now)
                .build();

        JobApplicationEntity saved = applicationRepository.save(application);
        log.info("求人応募: applicationId={}, postingId={}, userId={}", saved.getId(), postingId, userId);

        // 応募通知（Requester 宛）。DB 例外等に巻き込まれない設計とするため catch でログ出力に留める。
        try {
            notificationService.notifyApplied(saved, posting);
        } catch (Exception e) {
            log.warn("JOB_APPLIED 通知送信失敗（応募自体は成立）: applicationId={}, error={}",
                    saved.getId(), e.getMessage());
        }
        return saved;
    }

    /**
     * 応募を取り下げる。本人のみ許可、APPLIED → WITHDRAWN へ遷移。
     */
    @Transactional
    public JobApplicationEntity withdraw(Long applicationId, Long userId) {
        JobApplicationEntity app = findOrThrow(applicationId);
        if (!userId.equals(app.getApplicantUserId())) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        stateMachine.validate(app.getStatus(), JobApplicationStatus.WITHDRAWN);
        app.withdraw();
        JobApplicationEntity saved = applicationRepository.save(app);
        log.info("応募取り下げ: applicationId={}, userId={}", applicationId, userId);
        return saved;
    }

    /**
     * 応募を不採用にする。採否権限者のみ許可、APPLIED → REJECTED へ遷移。
     *
     * @param reason 不採用理由（任意）。現状 Entity 側に保存欄がないため MVP はログのみに記録する。
     */
    @Transactional
    public JobApplicationEntity reject(Long applicationId, String reason, Long userId) {
        JobApplicationEntity app = findOrThrow(applicationId);
        JobPostingEntity posting = postingRepository.findById(app.getJobPostingId())
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_NOT_FOUND));

        if (!jobPolicy.canDecideApplication(userId, posting)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        stateMachine.validate(app.getStatus(), JobApplicationStatus.REJECTED);
        app.reject(userId);
        JobApplicationEntity saved = applicationRepository.save(app);
        log.info("応募不採用: applicationId={}, userId={}, reason={}", applicationId, userId, reason);
        return saved;
    }

    // ---------------------------------------------------------------------
    // クエリ系
    // ---------------------------------------------------------------------

    /**
     * 求人に対する応募一覧を取得する。閲覧権限は採否権限者（Requester/ADMIN/jobs.manage 保有者）のみ。
     */
    public List<JobApplicationEntity> listByPosting(Long postingId, Long userId) {
        JobPostingEntity posting = postingRepository.findById(postingId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_NOT_FOUND));
        if (!jobPolicy.canDecideApplication(userId, posting)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        return applicationRepository.findByJobPostingIdOrderByAppliedAtDesc(postingId);
    }

    /**
     * 自分の応募履歴をページング取得する。
     */
    public Page<JobApplicationEntity> listMyApplications(Long userId, Pageable pageable) {
        return applicationRepository.findByApplicantUserId(userId, pageable);
    }

    /**
     * 応募を ID で取得する。見つからない場合は {@code JOB_APPLICATION_NOT_FOUND} を送出する。
     */
    public JobApplicationEntity findById(Long applicationId) {
        return findOrThrow(applicationId);
    }

    // ---------------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------------

    private JobApplicationEntity findOrThrow(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_APPLICATION_NOT_FOUND));
    }

    /**
     * MVP 対応の公開範囲かを判定する（canApply が false のとき、権限不足なのか未対応スコープなのかを区別する）。
     */
    private boolean isSupportedScope(JobPostingEntity posting) {
        return switch (posting.getVisibilityScope()) {
            case TEAM_MEMBERS, TEAM_MEMBERS_SUPPORTERS -> true;
            default -> false;
        };
    }

    private void validateSelfPr(String selfPr) {
        if (selfPr != null && selfPr.length() > MAX_SELF_PR_LENGTH) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
    }
}
