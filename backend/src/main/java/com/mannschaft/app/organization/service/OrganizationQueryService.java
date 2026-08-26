package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 組織ドメインの読み取り公開クエリサービス（他ドメインへ最小の boolean/primitive を提供する境界）。
 *
 * <p><b>目的</b>: 他ドメイン（例: billing の {@code ScopeClassificationService}）が
 * {@code OrganizationEntity} / {@code OrganizationRepository} を直接参照せず、
 * 「その組織が非営利か」という<b>意味のある boolean</b> だけを Service 経由で得られるようにする
 * （CLAUDE.md ドメイン境界の原則「異なるドメインの Entity を直接参照しない・データ取得は Service 経由」）。</p>
 *
 * <p>本サービスは組織ドメイン内で完結する（自ドメインの {@code OrganizationRepository} のみ参照）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationQueryService {

    private final OrganizationRepository organizationRepository;

    /**
     * 組織が非営利扱いか（{@code org_type} が {@code COMPANY} 以外）を返す。
     *
     * <p>不明組織（不在）は fail-safe で {@code false}（営利扱い＝無料枠を与えない）。
     * F20.1 の {@code free_for_nonprofit} 無料枠判定に用いる（README §3.3 / R-2）。
     * 本メソッドは org_type を<b>読むだけ</b>で自動変異させない（営利自動切替は Phase 2 保留）。</p>
     *
     * @param orgId 組織 ID
     * @return 非営利扱いなら true
     */
    public boolean isNonProfit(Long orgId) {
        if (orgId == null) {
            return false;
        }
        OrganizationEntity org = organizationRepository.findById(orgId).orElse(null);
        if (org == null || org.getOrgType() == null) {
            return false;
        }
        return org.getOrgType() != OrganizationEntity.OrgType.COMPANY;
    }
}
