package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 組織詳細レスポンスの入口（CMP-261004-1942）。組織の値にサポーター数（{@code social.supporterCount}）を合成する。
 *
 * <h2>トランザクションを持たない理由</h2>
 * <p>組織の値（organization ドメイン）とサポーター数（membership ドメインの集計）をまたいで組み立てるため、
 * 本クラスは {@code @Transactional} を付けず、{@link OrganizationService} と {@link MembershipService} が
 * それぞれのトランザクションで順に処理する（CLAUDE.md 原則 5・D-3T。{@link TeamAffiliationFacade} と同じ形）。</p>
 *
 * <h2>キャッシュとの関係</h2>
 * <p>サポーター数は {@code org-detail} キャッシュの外で毎回数える（応援・解除が即時に反映される）。
 * キャッシュから返ったレスポンスは書き換えず、{@code toBuilder()} で複製して合成する。</p>
 */
@Service
@RequiredArgsConstructor
public class OrganizationDetailFacade {

    private final OrganizationService organizationService;
    private final MembershipService membershipService;

    /** 組織詳細を slug で取得する（認可は呼び出し側の Controller が先に行う）。 */
    public ApiResponse<OrganizationResponse> getOrganization(String slug) {
        return withSocial(organizationService.getOrganization(slug));
    }

    /**
     * 組織を更新し、更新後の詳細を返す。
     *
     * <p>サポーター数は更新コミットより前に数える。組織の更新・slug 変更はサポーター数（membership ドメインの集計）
     * を変化させないため、順序を入れ替えても結果は変わらない一方、集計の失敗を更新前に検知でき、
     * 「更新はコミット済みだが集計失敗で 500」という不整合（保存済みなのにエラー）を避けられる。</p>
     */
    public ApiResponse<OrganizationResponse> updateOrganization(Long orgId, UpdateOrganizationRequest req) {
        long supporterCount = membershipService.countActiveSupporters(ScopeType.ORGANIZATION, orgId);
        return withSocial(organizationService.updateOrganization(orgId, req), supporterCount);
    }

    /** 組織 slug をリネームし、リネーム後の詳細を返す（サポーター数は更新前に数える。理由は {@link #updateOrganization} 参照）。 */
    public ApiResponse<OrganizationResponse> renameSlug(Long orgId, String newSlug) {
        long supporterCount = membershipService.countActiveSupporters(ScopeType.ORGANIZATION, orgId);
        return withSocial(organizationService.renameSlug(orgId, newSlug), supporterCount);
    }

    private ApiResponse<OrganizationResponse> withSocial(ApiResponse<OrganizationResponse> response) {
        OrganizationResponse org = response.getData();
        long supporterCount = membershipService.countActiveSupporters(ScopeType.ORGANIZATION, org.getNumericId());
        return withSocial(response, supporterCount);
    }

    private ApiResponse<OrganizationResponse> withSocial(ApiResponse<OrganizationResponse> response, long supporterCount) {
        OrganizationResponse org = response.getData();
        return ApiResponse.of(org.toBuilder()
                .social(new OrganizationResponse.OrgSocialDto(supporterCount))
                .build());
    }
}
