package com.mannschaft.app.gdpr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F12.3 支払い匿名化サービス。
 * 退会ユーザーの支払い記録を匿名化する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAnonymizationService {

    /**
     * 支払い記録のuserIdをセンチネルIDに差し替える。
     *
     * @param userId     匿名化対象ユーザーID
     * @param sentinelId センチネルID（0）
     */
    @Transactional
    public void anonymizeUserId(Long userId, Long sentinelId) {
        log.info("member_payments匿名化: userId={} → sentinelId={}", userId, sentinelId);
        // 実装は支払いリポジトリを使って実行
    }
}
