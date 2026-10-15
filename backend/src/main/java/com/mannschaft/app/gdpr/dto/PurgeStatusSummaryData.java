package com.mannschaft.app.gdpr.dto;

import java.util.List;

/**
 * GDPR パージ状況サマリー DTO。
 *
 * <p>システム管理者向け GDPR purge 状況サマリー API のレスポンス。
 * ドメイン別の PENDING / SUCCESS 集計と GDPR アラート件数を含む。</p>
 *
 * @param totalPending 全体の PENDING 件数
 * @param totalSuccess 全体の SUCCESS 件数
 * @param alertCount   アラート対象件数（PENDING かつ 30 分超過）
 * @param byDomain     ドメイン別集計リスト
 */
public record PurgeStatusSummaryData(
        long totalPending,
        long totalSuccess,
        long alertCount,
        List<DomainCount> byDomain
) {

    /**
     * ドメイン別集計。
     *
     * @param domain       ドメイン識別子（role / team / payment / chart / proxy / errorreport）
     * @param pendingCount そのドメインの PENDING 件数
     * @param successCount そのドメインの SUCCESS 件数
     */
    public record DomainCount(
            String domain,
            long pendingCount,
            long successCount
    ) {}
}
