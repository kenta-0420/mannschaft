package com.mannschaft.app.moderation.service;

import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F10.1.1 / P3b: 通報ドメインのスコープ別 stats 集約 Query Service（read-only）。
 *
 * <p>既存の {@code ReportActionService.getStats()} は全体集計でスコープを無視するため、
 * チーム/組織パネル管理者レンズ ⑥（{@code ADMIN_TEAM_REPORTS} / {@code ADMIN_ORG_REPORTS}）向けに
 * {@code scope_type + scope_id} で絞り込んだ「未対応／確認中」件数を返す（設計書 02 §2.2 ⑥ / §2.3 ⑥）。</p>
 *
 * <p>集約には既存の {@code ContentReportRepository.countByScopeTypeAndScopeIdAndStatus} を使い、
 * WHERE に必ず scope 列を含めるため、テナント越境（IDOR）は構造的に発生しない（設計書 04 §5）。
 * dashboard DTO への組み立ては呼び出し側（dashboard ファサード）で行い、本サービスはドメインローカルな
 * {@link ScopeReportStats} を返す（moderation → dashboard の逆依存回避）。</p>
 *
 * <p>本機能は読み取り集約のため承認ロジック・トランザクション・監査ログには触れない（原則5 遵守）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2 ⑥ / §2.3 ⑥</p>
 */
@Service
@RequiredArgsConstructor
public class ReportScopeStatsQueryService {

    private final ContentReportRepository reportRepository;

    /**
     * 指定スコープの通報 stats（未対応／確認中）を返す。
     *
     * @param scopeType スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId   スコープ ID（WHERE 必須・IDOR 防止）
     * @return 未対応(PENDING)・確認中(REVIEWING)件数
     */
    @Transactional(readOnly = true)
    public ScopeReportStats scopeStats(String scopeType, Long scopeId) {
        long pending = reportRepository.countByScopeTypeAndScopeIdAndStatus(
                scopeType, scopeId, ReportStatus.PENDING);
        long reviewing = reportRepository.countByScopeTypeAndScopeIdAndStatus(
                scopeType, scopeId, ReportStatus.REVIEWING);
        return new ScopeReportStats(pending, reviewing);
    }

    /**
     * スコープ別通報 stats（未対応件数・確認中件数）のドメインローカル値オブジェクト。
     * dashboard DTO（{@code AdminReportStatsResponse}）への変換は dashboard ファサードが担う。
     *
     * @param pendingCount   未対応件数（ReportStatus.PENDING）
     * @param reviewingCount 確認中件数（ReportStatus.REVIEWING）
     */
    public record ScopeReportStats(long pendingCount, long reviewingCount) {
    }
}
