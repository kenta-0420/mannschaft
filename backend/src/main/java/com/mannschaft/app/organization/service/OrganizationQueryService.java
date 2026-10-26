package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.OrganizationQueryMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final OrganizationQueryMapper organizationQueryMapper;

    /** 他ドメイン向けの一覧用情報。Entityやそのenumを境界外へ公開しない。 */
    public record OrganizationListItem(Long id, String slug, String name, String visibility,
                                       LocalDateTime archivedAt) {
    }

    /**
     * 一覧用の組織情報を1 SQLで取得する。不存在・論理削除済みの組織は含めない。
     * アーカイブ状態は呼び出し元のincludeArchived判定に使うため、そのまま返す。
     *
     * @param orgIds 一覧の対象組織ID
     * @return 組織IDごとの一覧用情報。空入力は空のMap
     */
    public Map<Long, OrganizationListItem> findListItemsByIds(Collection<Long> orgIds) {
        Map<Long, OrganizationListItem> result = new LinkedHashMap<>();
        if (orgIds == null || orgIds.isEmpty()) {
            return result;
        }
        for (OrganizationEntity org : organizationRepository.findAllById(orgIds)) {
            OrganizationListItem item = organizationQueryMapper.toListItem(org);
            result.put(item.id(), item);
        }
        return result;
    }

    /** 他ドメイン向けの組織の表示用要約（ID・名前・slug だけ）。 */
    public record OrganizationSummary(Long id, String name, String slug) {
    }

    /**
     * 組織 ID 群の表示用要約を返す（存在しない・削除済みの ID は結果に含まれない。順序は引数の順）。
     *
     * @param orgIds 組織 ID の一覧
     */
    public java.util.List<OrganizationSummary> findSummariesByIds(java.util.Collection<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return java.util.List.of();
        }
        java.util.Map<Long, OrganizationEntity> byId = new java.util.HashMap<>();
        for (OrganizationEntity org : organizationRepository.findAllById(orgIds)) {
            byId.put(org.getId(), org);
        }
        java.util.List<OrganizationSummary> result = new java.util.ArrayList<>();
        for (Long id : orgIds) {
            OrganizationEntity org = byId.get(id);
            if (org != null) {
                result.add(new OrganizationSummary(org.getId(), org.getName(), org.getSlug()));
            }
        }
        return result;
    }

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
