package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.3 アカウント物理削除サービス。
 * 退会後30日以上経過したユーザーを物理削除する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountPurgeService {

    private final UserRepository userRepository;
    private final ChartAnonymizationService chartAnonymizationService;
    private final PaymentAnonymizationService paymentAnonymizationService;

    /** 物理削除の閾値: 退会から30日後 */
    private static final long PURGE_THRESHOLD_DAYS = 30;

    /** 1バッチの最大処理件数 */
    private static final int BATCH_SIZE = 100;

    /**
     * 物理削除バッチを実行する。
     *
     * @param dryRun trueの場合、実際の削除は実行しない（ログのみ）
     */
    @Transactional
    public void runPurge(boolean dryRun) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(PURGE_THRESHOLD_DAYS);
        List<UserEntity> targets = userRepository.findPurgeTargets(cutoff, PageRequest.of(0, BATCH_SIZE));

        log.info("物理削除対象ユーザー: {}件 (dryRun={})", targets.size(), dryRun);

        if (dryRun) {
            log.info("DRY-RUN モード: 実際の削除はスキップ");
            return;
        }

        for (UserEntity user : targets) {
            try {
                purgeUser(user);
            } catch (Exception e) {
                log.error("ユーザー物理削除失敗: userId={}", user.getId(), e);
            }
        }
    }

    /**
     * 1ユーザーを物理削除する。
     *
     * @param user 削除対象ユーザー
     */
    private void purgeUser(UserEntity user) {
        Long userId = user.getId();
        log.info("Phase1: ユーザーデータ匿名化開始 userId={}", userId);

        // Phase2: chart_records の匿名化
        chartAnonymizationService.anonymizeCustomerUserId(userId);

        // Phase3: member_payments のセンチネル差替
        paymentAnonymizationService.anonymizeUserId(userId, com.mannschaft.app.auth.UserConstants.SENTINEL_USER_ID);

        // Phase4: ユーザー物理削除
        userRepository.delete(user);

        log.info("ユーザー物理削除完了: userId={}", userId);
    }
}
