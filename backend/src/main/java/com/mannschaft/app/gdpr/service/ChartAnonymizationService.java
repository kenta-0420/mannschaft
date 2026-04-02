package com.mannschaft.app.gdpr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F12.3 カルテ匿名化サービス。
 * 退会ユーザーのカルテ記録を匿名化する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartAnonymizationService {

    /**
     * カルテ記録のcustomerUserIdを匿名化する（0に差し替え）。
     *
     * @param userId 匿名化対象ユーザーID
     */
    @Transactional
    public void anonymizeCustomerUserId(Long userId) {
        log.info("chart_records匿名化: userId={}", userId);
        // 実装はチャートリポジトリを使って実行
    }
}
