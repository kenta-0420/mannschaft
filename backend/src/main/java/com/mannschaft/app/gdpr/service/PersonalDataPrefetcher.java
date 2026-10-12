package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.dto.PrefetchedPersonalData;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * GDPR データエクスポートの個人データのうち、<b>エクスポートのトランザクションの外</b>で取得するものを集める
 * （{@link PrefetchedPersonalData}。F01.2.1 2-D）。
 *
 * <p>本クラスはトランザクションを持たない。各ドメインの公開読み取りサービス（それぞれ自ドメインの読み取り
 * トランザクション）を呼ぶだけで、gdpr のトランザクション（{@code DataExportService#processExportAsync}）から
 * 他ドメインの Repository へ届かせない（CLAUDE.md 原則5・D-3T）。呼び出し元は {@code GdprController}。</p>
 */
@Component
@RequiredArgsConstructor
public class PersonalDataPrefetcher {

    /** {@code PersonalDataCollector} のカテゴリキー（事前取得が要るもの）。 */
    static final String MEMBERSHIPS = "memberships";

    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    /**
     * 収集対象のカテゴリのうち、事前取得の要るものを取得する。
     *
     * @param categories 収集カテゴリ（null または空＝全カテゴリ）
     */
    public PrefetchedPersonalData prefetch(Long userId, Set<String> categories) {
        boolean all = categories == null || categories.isEmpty();
        return new PrefetchedPersonalData(
                all || categories.contains(MEMBERSHIPS) ? teamOrgMembershipQueryService.collectPersonalData(userId) : null);
    }
}
