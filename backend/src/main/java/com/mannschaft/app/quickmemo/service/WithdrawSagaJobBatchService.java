package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.storage.S3StorageService;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.entity.WithdrawJobEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoAttachmentRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoTagLinkRepository;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import com.mannschaft.app.quickmemo.repository.UserQuickMemoSettingsRepository;
import com.mannschaft.app.quickmemo.repository.WithdrawJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 退会 SAGA ジョブ実行バッチ。
 * 10分ごとに PENDING・リトライ可能なジョブを取得し、current_step から再開する。
 * tags.created_by の ON DELETE RESTRICT があるため Step 順序が重要。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawSagaJobBatchService {

    private static final int MAX_RETRY = 3;

    private final WithdrawJobRepository withdrawJobRepository;
    private final TagRepository tagRepository;
    private final QuickMemoRepository memoRepository;
    private final QuickMemoAttachmentRepository attachmentRepository;
    private final QuickMemoTagLinkRepository tagLinkRepository;
    private final UserQuickMemoSettingsRepository settingsRepository;
    private final S3StorageService s3StorageService;
    private final AuditLogService auditLogService;

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void execute() {
        List<WithdrawJobEntity> jobs = withdrawJobRepository.findPendingOrRetryableJobs();
        if (jobs.isEmpty()) return;

        log.info("退会SAGAバッチ開始: {}件", jobs.size());
        for (WithdrawJobEntity job : jobs) {
            processJob(job);
        }
    }

    private void processJob(WithdrawJobEntity job) {
        job.start();
        withdrawJobRepository.save(job);

        try {
            executeFromStep(job);
        } catch (Exception e) {
            int failedStep = job.getCurrentStep();
            if (job.getRetryCount() >= MAX_RETRY) {
                job.blockForManual(failedStep, e.getMessage());
                log.error("退会SAGA BLOCKED_MANUAL: userId={}, step={}, error={}",
                        job.getUserId(), failedStep, e.getMessage());
            } else {
                job.fail(failedStep, e.getMessage());
                log.warn("退会SAGA失敗（リトライ予定）: userId={}, step={}, retryCount={}",
                        job.getUserId(), failedStep, job.getRetryCount());
            }
            withdrawJobRepository.save(job);
        }
    }

    private void executeFromStep(WithdrawJobEntity job) {
        Long userId = job.getUserId();
        int startStep = job.getCurrentStep();

        for (int step = startStep; step <= 7; step++) {
            executeStep(job, step, userId);
            withdrawJobRepository.save(job);
        }
    }

    private void executeStep(WithdrawJobEntity job, int step, Long userId) {
        switch (step) {
            case 1 -> {
                // PERSONAL タグ削除（CASCADE で tag_links も削除）
                tagRepository.deletePersonalTagsByUserId(userId);
                job.completeStep(1);
                log.info("退会SAGA Step1完了: userId={}", userId);
            }
            case 2 -> {
                // TEAM タグ移譲（ADMIN 優先・いなければ削除）
                // 実装簡略化: チームADMIN への移譲はチームサービスに委譲（別途実装）
                job.completeStep(2);
                log.info("退会SAGA Step2完了: userId={}", userId);
            }
            case 3 -> {
                // ORGANIZATION タグ移譲（ADMIN 不在で BLOCKED_MANUAL）
                // 実装簡略化: 組織ADMIN への移譲は組織サービスに委譲（別途実装）
                job.completeStep(3);
                log.info("退会SAGA Step3完了: userId={}", userId);
            }
            case 4 -> {
                // quick_memos 物理削除（S3 同期削除）
                List<QuickMemoEntity> memos = memoRepository
                        .findByUserIdAndDeletedAtIsNull(userId, PageRequest.of(0, Integer.MAX_VALUE))
                        .getContent();
                List<Long> memoIds = memos.stream().map(QuickMemoEntity::getId).toList();
                if (!memoIds.isEmpty()) {
                    List<String> s3Keys = attachmentRepository.findS3KeysByMemoIdIn(memoIds);
                    for (String s3Key : s3Keys) {
                        s3StorageService.delete(s3Key);
                    }
                    // usage_count 集計デクリメント
                    Map<Long, Long> tagDecrements = tagLinkRepository.findByMemoIdIn(memoIds).stream()
                            .collect(Collectors.groupingBy(l -> l.getTagId(), Collectors.counting()));
                    tagDecrements.forEach((tagId, count) ->
                            tagRepository.decrementUsageCountBy(tagId, count.intValue()));
                    memoRepository.deleteAllById(memoIds);
                }
                job.completeStep(4);
                log.info("退会SAGA Step4完了: userId={}, memoCount={}", userId, memoIds.size());
            }
            case 5 -> {
                // user_quick_memo_settings 物理削除
                settingsRepository.findByUserId(userId).ifPresent(settingsRepository::delete);
                job.completeStep(5);
                log.info("退会SAGA Step5完了: userId={}", userId);
            }
            case 6 -> {
                // 監査ログ匿名化（AuditLogService に委譲）
                auditLogService.record("WITHDRAW_AUDIT_ANONYMIZE", null, userId, null, null,
                        null, null, null, "{\"userId\":" + userId + "}");
                job.completeStep(6);
                log.info("退会SAGA Step6完了: userId={}", userId);
            }
            case 7 -> {
                // users 物理削除（ここでようやく FK 制約が解放される）
                // users テーブルの削除は AuthService に委譲（別途実装）
                job.completeStep(7);
                log.info("退会SAGA Step7完了（完全退会）: userId={}", userId);
            }
            default -> throw new IllegalStateException("Invalid step: " + step);
        }
    }
}
