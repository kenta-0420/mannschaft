package com.mannschaft.app.resume.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.entity.GdprS3PurgeFailureEntity;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.gdpr.repository.GdprS3PurgeFailureRepository;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.resume.entity.ResumeEntity;
import com.mannschaft.app.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AccountPurgedEvent} を購読し、resume ドメインの全レコードと
 * R2 オブジェクトを物理削除する。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §9.2.1
 *
 * <p><b>三重防御パターン（{@link com.mannschaft.app.chart.event.ChartPurgeEventListener} と同型）:</b>
 * <ul>
 *   <li>{@code @Async("purge-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — gdpr 側コミット成立後に実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX</li>
 * </ul>
 *
 * <p><b>削除対象:</b>
 * <ul>
 *   <li>R2 オブジェクト: 証明写真 ({@code photo_key}) + 生成物
 *       ({@code rirekisho} / {@code shokumukeirekisho}) × ({@code pdf} / {@code xlsx})</li>
 *   <li>DB: {@code resumes} テーブル（{@code deleted_at} 問わず全件）。
 *       {@code ON DELETE CASCADE} により子テーブル
 *       ({@code resume_educations / resume_careers / resume_qualifications / resume_skills})
 *       も連鎖削除される</li>
 * </ul>
 *
 * <p>R2 削除失敗は {@link GdprS3PurgeFailureEntity} に記録し、
 * 夜次補正バッチ ({@code GdprPurgeAuditBatchService}) がリトライする。
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase B / §9.2（resume ドメイン対応）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumePurgeEventListener {

    private final ResumeRepository resumeRepository;
    private final StorageService storageService;
    private final GdprS3PurgeFailureRepository gdprS3PurgeFailureRepository;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * {@link AccountPurgedEvent} を購読し、resume ドメインの全レコードと
     * R2 オブジェクトを物理削除する。
     *
     * <p>例外発生時は WARN ログのみで伝播させない（GDPR 30 日タイムリミットを優先し、
     * 他リスナーの処理を妨げない）。
     * 失敗分は夜次補正バッチ（Phase D）で再処理する運用とする。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会確定者の履歴書レコードと R2 オブジェクトが削除されず、GDPR 第17条の消去期限を直接破ったうえ外部ストレージに個人データが残留する")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        boolean success = false;
        try {
            // 1) DB から R2 キーを列挙（photo_key + 生成物の確定列挙）
            List<ResumeEntity> resumes = resumeRepository.findAllByUserId(userId);
            List<String> keys = buildStorageKeys(userId, resumes);

            // 2) R2 削除（失敗は GdprS3PurgeFailureEntity に記録し、夜次リトライ対象とする）
            if (!keys.isEmpty()) {
                try {
                    storageService.deleteAll(keys);
                } catch (Exception e) {
                    log.warn("ユーザー退会 resume R2 削除失敗 userId={}, error={}", userId, e.getMessage(), e);
                    keys.forEach(k -> {
                        GdprS3PurgeFailureEntity f = new GdprS3PurgeFailureEntity();
                        f.setUserId(userId);
                        f.setS3Key(k);
                        f.setFailedAt(LocalDateTime.now());
                        f.setLastError(e.getMessage() != null
                                ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500))
                                : "unknown");
                        gdprS3PurgeFailureRepository.save(f);
                    });
                }
            }

            // 3) DB 物理削除（CASCADE で子テーブルも自動削除）
            resumeRepository.deletePhysicallyByUserId(userId);
            log.info("ユーザー退会 resume purge 完了: userId={}, deletedResumes={}", userId, resumes.size());
            success = true;

        } catch (Exception e) {
            log.warn("ユーザー退会 resume purge 失敗 userId={}, error={}", userId, e.getMessage(), e);
        }

        // 4) completion_status を SUCCESS に更新（ChartPurgeEventListener と同型）
        if (success) {
            completionStatusRepository.findByUserIdAndDomainName(userId, "resume")
                    .ifPresent(entity -> {
                        entity.setStatus("SUCCESS");
                        entity.setCompletedAt(LocalDateTime.now());
                        completionStatusRepository.save(entity);
                    });
        }
    }

    /**
     * 管理者からの手動 retry 用。{@link #on(AccountPurgedEvent)} と同じドメイン操作を実行するが、
     * {@code completionStatusRepository} の更新は {@code GdprPurgeRetryService} が担う。
     *
     * @param userId retry 対象ユーザー ID
     * @return true=成功、false=失敗
     */
    @Transactional
    public boolean retryPurge(Long userId) {
        try {
            List<ResumeEntity> resumes = resumeRepository.findAllByUserId(userId);
            List<String> keys = buildStorageKeys(userId, resumes);
            if (!keys.isEmpty()) {
                storageService.deleteAll(keys);
            }
            resumeRepository.deletePhysicallyByUserId(userId);
            return true;
        } catch (Exception e) {
            log.warn("resume purge retry: 削除失敗 userId={}", userId, e);
            return false;
        }
    }

    /**
     * R2 ストレージキーを列挙する。
     *
     * <p>削除対象:
     * <ul>
     *   <li>証明写真: {@code resume.getPhotoKey()} が non-null の場合</li>
     *   <li>生成物: {@code rirekisho} / {@code shokumukeirekisho} × {@code pdf} / {@code xlsx}
     *       の 4 種。StorageService にプレフィックス一括削除 API が存在しないため、
     *       確定的に全パターンを列挙して {@code deleteAll} に渡す（§9.2.3）</li>
     * </ul>
     *
     * @param userId  対象ユーザー ID
     * @param resumes 対象ユーザーの全履歴書レコード
     * @return 削除対象ストレージキーのリスト
     */
    private List<String> buildStorageKeys(Long userId, List<ResumeEntity> resumes) {
        List<String> keys = new ArrayList<>();
        for (ResumeEntity resume : resumes) {
            String rid = resume.getId().toString();

            // 証明写真
            if (resume.getPhotoKey() != null) {
                keys.add(resume.getPhotoKey());
            }

            // 生成物 4 種（rirekisho/shokumukeirekisho × pdf/xlsx）
            for (String type : List.of("rirekisho", "shokumukeirekisho")) {
                for (String ext : List.of("pdf", "xlsx")) {
                    keys.add("user/" + userId + "/resume/" + rid + "/" + type + "." + ext);
                }
            }
        }
        return keys;
    }
}
