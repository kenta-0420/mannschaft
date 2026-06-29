package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.quota.dto.StorageScopeUsage;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StorageUsageQueryService} のドメインユニットテスト（純 Mockito・試練先行）。
 *
 * <ul>
 *   <li>AC-4: subscription 未作成スコープは使用量 0・件数 0 とし、scope_level のデフォルトプランの
 *       included/max を適用する。かつ <b>subscription を新規保存しない（副作用なし）</b>。</li>
 *   <li>AC-7: {@code includedBytes == 0} のとき usagePercent はゼロ除算せず 0 を返す。</li>
 *   <li>AC-8: PERSONAL / TEAM / ORGANIZATION 以外の scope_type を列挙・取得しない
 *       （TOURNAMENT 等は主催 ORGANIZATION の subscription に集約済みのため個別に問い合わせない）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageUsageQueryService ドメインUT（試練）")
class StorageUsageQueryServiceTest {

    private static final Long USER_ID = 100L;
    private static final long GB = 1024L * 1024L * 1024L;

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private StorageSubscriptionRepository subscriptionRepository;
    @Mock
    private StoragePlanRepository planRepository;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private StorageUsageQueryService service;

    private StoragePlanEntity plan(Long id, String level, Long included, Long max) {
        return StoragePlanEntity.builder()
                .id(id)
                .name(level + "-default")
                .scopeLevel(level)
                .includedBytes(included)
                .maxBytes(max)
                .isDefault(true)
                .build();
    }

    private StorageSubscriptionEntity sub(String scopeType, Long scopeId, Long planId,
                                          Long usedBytes, Integer fileCount) {
        return StorageSubscriptionEntity.builder()
                .id(scopeId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .planId(planId)
                .usedBytes(usedBytes)
                .fileCount(fileCount)
                .build();
    }

    @Test
    @DisplayName("AC-4: subscription 未作成のチームは 0 + デフォルトプラン適用、行を保存しない")
    void untreatedScope_usesZeroAndDefaultPlan_withoutSaving() {
        when(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).thenReturn(Set.of(10L));
        when(accessControlService.findAffiliatedScopeIds(USER_ID, "ORGANIZATION")).thenReturn(Set.of());
        when(teamService.getNamesByIds(Set.of(10L))).thenReturn(Map.of(10L, "チームA"));
        when(teamService.getSlugsByIds(Set.of(10L))).thenReturn(Map.of(10L, "team-a"));

        // PERSONAL も TEAM も subscription 行が無い（未作成）。
        when(subscriptionRepository.findByScopeTypeAndScopeIdIn(eq("PERSONAL"), any()))
                .thenReturn(List.of());
        when(subscriptionRepository.findByScopeTypeAndScopeIdIn(eq("TEAM"), any()))
                .thenReturn(List.of());

        when(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("PERSONAL"))
                .thenReturn(java.util.Optional.of(plan(1L, "PERSONAL", 1 * GB, 2 * GB)));
        when(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("TEAM"))
                .thenReturn(java.util.Optional.of(plan(2L, "TEAM", 10 * GB, null)));

        List<StorageScopeUsage> result = service.getMyStorageUsage(USER_ID);

        StorageScopeUsage team = result.stream()
                .filter(u -> "TEAM".equals(u.scopeType()))
                .findFirst().orElseThrow();
        assertThat(team.scopeId()).isEqualTo(10L);
        assertThat(team.usedBytes()).isZero();
        assertThat(team.fileCount()).isZero();
        assertThat(team.includedBytes()).isEqualTo(10 * GB);
        assertThat(team.maxBytes()).isNull();
        assertThat(team.usagePercent()).isZero();

        // 副作用なし: subscription を新規保存しない。
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-7: includedBytes=0 でも usagePercent はゼロ除算せず 0")
    void zeroIncludedBytes_doesNotDivideByZero() {
        // 所属チーム・組織は無し（個人のみ検証）。
        when(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).thenReturn(Set.of());
        when(accessControlService.findAffiliatedScopeIds(USER_ID, "ORGANIZATION")).thenReturn(Set.of());

        // PERSONAL に subscription があり、参照プランの included_bytes が 0。
        when(subscriptionRepository.findByScopeTypeAndScopeIdIn(eq("PERSONAL"), any()))
                .thenReturn(List.of(sub("PERSONAL", USER_ID, 5L, 500L, 3)));
        when(planRepository.findAllById(Set.of(5L)))
                .thenReturn(List.of(plan(5L, "PERSONAL", 0L, null)));

        List<StorageScopeUsage> result = service.getMyStorageUsage(USER_ID);

        StorageScopeUsage personal = result.stream()
                .filter(u -> "PERSONAL".equals(u.scopeType()))
                .findFirst().orElseThrow();
        assertThat(personal.usedBytes()).isEqualTo(500L);
        assertThat(personal.includedBytes()).isZero();
        assertThat(personal.usagePercent())
                .isEqualTo(0.0)
                .isNotNaN()
                .isFinite();
    }

    @Test
    @DisplayName("AC-8: PERSONAL/TEAM/ORGANIZATION 以外の scope_type は列挙・取得しない")
    void onlyPersonalTeamOrgScopesAreQueried() {
        when(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).thenReturn(Set.of(10L));
        when(accessControlService.findAffiliatedScopeIds(USER_ID, "ORGANIZATION")).thenReturn(Set.of(20L));
        when(teamService.getNamesByIds(Set.of(10L))).thenReturn(Map.of(10L, "T"));
        when(teamService.getSlugsByIds(Set.of(10L))).thenReturn(Map.of(10L, "t"));
        when(organizationService.getNamesByIds(Set.of(20L))).thenReturn(Map.of(20L, "O"));
        when(organizationService.getSlugsByIds(Set.of(20L))).thenReturn(Map.of(20L, "o"));
        when(subscriptionRepository.findByScopeTypeAndScopeIdIn(anyString(), any()))
                .thenReturn(List.of());
        when(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull(anyString()))
                .thenReturn(java.util.Optional.of(plan(9L, "X", 1 * GB, null)));

        List<StorageScopeUsage> result = service.getMyStorageUsage(USER_ID);

        // 返るのは PERSONAL / TEAM / ORGANIZATION のみ。
        assertThat(result).extracting(StorageScopeUsage::scopeType)
                .containsExactlyInAnyOrder("PERSONAL", "TEAM", "ORGANIZATION");

        // TOURNAMENT 等の scope_type は問い合わせない（主催 ORGANIZATION に集約済み）。
        verify(subscriptionRepository, never())
                .findByScopeTypeAndScopeIdIn(eq("TOURNAMENT"), any());
        verify(subscriptionRepository, never())
                .findByScopeTypeAndScopeIdIn(eq("TOURNAMENT_DIVISION"), any());
        // findAffiliatedScopeIds も TEAM / ORGANIZATION のみ（TOURNAMENT 等は列挙しない）。
        verify(accessControlService, never()).findAffiliatedScopeIds(eq(USER_ID), eq("TOURNAMENT"));
    }
}
