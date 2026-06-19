package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.dto.ScopeTabItemResponse;
import com.mannschaft.app.dashboard.dto.ScopeTabOrderUpdateRequest;
import com.mannschaft.app.dashboard.dto.ScopeTabPageResponse;
import com.mannschaft.app.dashboard.entity.DashboardScopeTabOrderEntity;
import com.mannschaft.app.dashboard.repository.DashboardScopeTabOrderRepository;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link DashboardScopeTabService} の単体テスト。
 *
 * <p>並び順（保存済み→未保存補完）・6 件ページング境界・退会スコープ除外・フォルダフィルタ・
 * updateOrder の非所属混入 → 全体 403・sortOrder 重複 → 400 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardScopeTabService 単体テスト")
class DashboardScopeTabServiceTest {

    @Mock private DashboardScopeTabOrderRepository scopeTabOrderRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private MyScopeFolderRepository scopeFolderRepository;
    @Mock private MyScopeFolderItemRepository scopeFolderItemRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private DashboardScopeTabService service;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // ヘルパー
    // ========================================

    private MembershipEntity activeMembership(Long scopeId, com.mannschaft.app.membership.domain.ScopeType type,
                                              LocalDateTime joinedAt) {
        return MembershipEntity.builder()
                .userId(USER_ID)
                .scopeType(type)
                .scopeId(scopeId)
                .joinedAt(joinedAt)
                .build();
    }

    private DashboardScopeTabOrderEntity savedOrder(Long scopeId, String scopeType, int sortOrder) {
        return DashboardScopeTabOrderEntity.builder()
                .userId(USER_ID)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sortOrder(sortOrder)
                .build();
    }

    private TeamEntity team(Long id, String name, String iconUrl) {
        return TeamEntity.builder().name(name).iconUrl(iconUrl).build();
    }

    private void stubTeamNames() {
        lenient().when(teamRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.of(team(id, "Team-" + id, null));
        });
        // findAllById: リクエストされた id 集合をすべて「実在」として返す（論理削除なし想定）。
        // TeamEntity の getId() は BaseEntity から継承するため、Mockito.mock で id を注入する。
        // lenient().when() は Mockito.OngoingStubbing を返すため thenAnswer を使う。
        lenient().when(teamRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<TeamEntity> result = new ArrayList<>();
            StreamSupport.stream(ids.spliterator(), false).forEach(id -> {
                TeamEntity mock = org.mockito.Mockito.mock(TeamEntity.class);
                org.mockito.Mockito.when(mock.getId()).thenReturn(id);
                result.add(mock);
            });
            return result;
        });
    }

    // ========================================
    // GET /scope-tabs 並び順
    // ========================================

    @Nested
    @DisplayName("getScopeTabs 並び順")
    class OrderingTests {

        @Test
        @DisplayName("保存済み行(sort_order昇順) → 未保存所属(joined_at降順)の順で並ぶ")
        void savedThenUnsavedByJoinedAtDesc() {
            // 所属: 10, 20, 30, 40（joined_at 降順で 40,30,20,10 が返る想定）
            given(membershipRepository.findActiveByUserAndScopeType(eq(USER_ID), any()))
                    .willReturn(List.of(
                            activeMembership(40L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(1)),
                            activeMembership(30L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(2)),
                            activeMembership(20L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(3)),
                            activeMembership(10L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(4))));
            // 保存済み: 20(order0), 10(order1)
            given(scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(USER_ID, "TEAM"))
                    .willReturn(List.of(savedOrder(20L, "TEAM", 0), savedOrder(10L, "TEAM", 1)));
            stubTeamNames();

            ScopeTabPageResponse res = service.getScopeTabs("TEAM", 0, null);

            // 先頭2件=保存済み(20,10)、末尾=未保存(joined_at降順 40,30)
            assertThat(res.items()).extracting(ScopeTabItemResponse::scopeId)
                    .containsExactly(20L, 10L, 40L, 30L);
            assertThat(res.totalCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("scopeType 小文字でも正規化されて受理される")
        void scopeTypeLowercaseNormalized() {
            given(membershipRepository.findActiveByUserAndScopeType(eq(USER_ID), any()))
                    .willReturn(List.of());
            given(scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(USER_ID, "TEAM"))
                    .willReturn(List.of());

            ScopeTabPageResponse res = service.getScopeTabs("team", 0, null);

            assertThat(res.items()).isEmpty();
            assertThat(res.totalCount()).isZero();
        }

        @Test
        @DisplayName("不正な scopeType は SCOPE_TAB_003")
        void invalidScopeType() {
            assertThatThrownBy(() -> service.getScopeTabs("PERSONAL", 0, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("スコープ種別");
        }
    }

    // ========================================
    // ページング境界（6件固定）
    // ========================================

    @Nested
    @DisplayName("getScopeTabs ページング")
    class PagingTests {

        private void stub14Teams() {
            List<MembershipEntity> ms = new ArrayList<>();
            for (long i = 1; i <= 14; i++) {
                ms.add(activeMembership(i, com.mannschaft.app.membership.domain.ScopeType.TEAM,
                        LocalDateTime.now().minusMinutes(i)));
            }
            given(membershipRepository.findActiveByUserAndScopeType(eq(USER_ID), any())).willReturn(ms);
            given(scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(USER_ID, "TEAM"))
                    .willReturn(List.of());
            stubTeamNames();
        }

        @Test
        @DisplayName("14件 page0 は6件、has_next=true/has_prev=false")
        void page0() {
            stub14Teams();
            ScopeTabPageResponse res = service.getScopeTabs("TEAM", 0, null);
            assertThat(res.items()).hasSize(6);
            assertThat(res.totalCount()).isEqualTo(14);
            assertThat(res.totalPages()).isEqualTo(3);
            assertThat(res.hasNext()).isTrue();
            assertThat(res.hasPrev()).isFalse();
        }

        @Test
        @DisplayName("14件 page2 は2件、has_next=false/has_prev=true")
        void lastPage() {
            stub14Teams();
            ScopeTabPageResponse res = service.getScopeTabs("TEAM", 2, null);
            assertThat(res.items()).hasSize(2);
            assertThat(res.hasNext()).isFalse();
            assertThat(res.hasPrev()).isTrue();
        }

        @Test
        @DisplayName("総ページ数超過の page は空リスト（エラーにしない）")
        void overflowPage() {
            stub14Teams();
            ScopeTabPageResponse res = service.getScopeTabs("TEAM", 99, null);
            assertThat(res.items()).isEmpty();
            assertThat(res.hasNext()).isFalse();
        }
    }

    // ========================================
    // 退会スコープ除外 / フォルダフィルタ
    // ========================================

    @Nested
    @DisplayName("getScopeTabs 除外・フィルタ")
    class FilterTests {

        @Test
        @DisplayName("退会した(現所属でない)保存済みスコープは除外される")
        void withdrawnScopeExcluded() {
            // 現所属は 10 のみ。保存済みには退会済 99 と現役 10 が残存。
            given(membershipRepository.findActiveByUserAndScopeType(eq(USER_ID), any()))
                    .willReturn(List.of(activeMembership(10L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now())));
            given(scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(USER_ID, "TEAM"))
                    .willReturn(List.of(savedOrder(99L, "TEAM", 0), savedOrder(10L, "TEAM", 1)));
            stubTeamNames();

            ScopeTabPageResponse res = service.getScopeTabs("TEAM", 0, null);

            assertThat(res.items()).extracting(ScopeTabItemResponse::scopeId).containsExactly(10L);
            assertThat(res.totalCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("folderId 指定時は当該フォルダの scope のみに絞り込まれる")
        void folderFilter() {
            given(membershipRepository.findActiveByUserAndScopeType(eq(USER_ID), any()))
                    .willReturn(List.of(
                            activeMembership(10L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(1)),
                            activeMembership(20L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(2)),
                            activeMembership(30L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(3))));
            given(scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(USER_ID, "TEAM"))
                    .willReturn(List.of());

            MyScopeFolderEntity folder = MyScopeFolderEntity.builder()
                    .userId(USER_ID)
                    .scopeType(com.mannschaft.app.scopefolder.entity.ScopeType.TEAM)
                    .name("お気に入り")
                    .build();
            given(scopeFolderRepository.findByIdAndUserIdAndDeletedAtIsNull(7L, USER_ID))
                    .willReturn(Optional.of(folder));
            given(scopeFolderItemRepository.findByFolderIdOrderBySortOrder(7L))
                    .willReturn(List.of(folderItem(10L), folderItem(30L)));
            stubTeamNames();

            ScopeTabPageResponse res = service.getScopeTabs("TEAM", 0, 7L);

            assertThat(res.items()).extracting(ScopeTabItemResponse::scopeId)
                    .containsExactlyInAnyOrder(10L, 30L);
            assertThat(res.totalCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("membership は存在するが team が論理削除済み（孤児）の場合は一覧から除外される")
        void orphanMembershipExcluded() {
            // activeScopeIds: 10（実在）, 175（孤児: team 削除済み）, 159（孤児）
            given(membershipRepository.findActiveByUserAndScopeType(eq(USER_ID), any()))
                    .willReturn(List.of(
                            activeMembership(10L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now()),
                            activeMembership(175L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(1)),
                            activeMembership(159L, com.mannschaft.app.membership.domain.ScopeType.TEAM, LocalDateTime.now().minusDays(2))));
            given(scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(USER_ID, "TEAM"))
                    .willReturn(List.of());
            // findById は個別取得（buildItem 内）
            lenient().when(teamRepository.findById(10L)).thenReturn(Optional.of(team(10L, "Team-10", null)));
            // findAllById: 10 のみ実在、175 と 159 は論理削除済み（結果に含まれない）。
            // @SQLRestriction により論理削除済みは findAllById の結果に含まれない。
            // Mockito.mock で getId() = 10L を返すモックを使い、existingScopeIds = {10} を確定させる。
            given(teamRepository.findAllById(any())).willAnswer(inv -> {
                TeamEntity mock10 = org.mockito.Mockito.mock(TeamEntity.class);
                org.mockito.Mockito.when(mock10.getId()).thenReturn(10L);
                return List.of(mock10);
            });

            ScopeTabPageResponse res = service.getScopeTabs("TEAM", 0, null);

            // 孤児 membership (175, 159) は除外され、実在する 10 のみが返る。
            assertThat(res.items()).extracting(ScopeTabItemResponse::scopeId).containsExactly(10L);
            assertThat(res.totalCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("他人所有/不在フォルダは SCOPE_TAB_004")
        void foreignFolder() {
            lenient().when(membershipRepository.findActiveByUserAndScopeType(eq(USER_ID), any()))
                    .thenReturn(List.of());
            lenient().when(scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(USER_ID, "TEAM"))
                    .thenReturn(List.of());
            given(scopeFolderRepository.findByIdAndUserIdAndDeletedAtIsNull(999L, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getScopeTabs("TEAM", 0, 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("フォルダ");
        }

        private MyScopeFolderItemEntity folderItem(Long scopeId) {
            return MyScopeFolderItemEntity.builder()
                    .folderId(7L)
                    .scopeId(scopeId)
                    .sortOrder(0)
                    .build();
        }
    }

    // ========================================
    // PUT /scope-tabs/order
    // ========================================

    @Nested
    @DisplayName("updateOrder")
    class UpdateOrderTests {

        private ScopeTabOrderUpdateRequest req(String scopeType, long[][] pairs) {
            ScopeTabOrderUpdateRequest r = new ScopeTabOrderUpdateRequest();
            r.setScopeType(scopeType);
            List<ScopeTabOrderUpdateRequest.OrderItem> items = new ArrayList<>();
            for (long[] p : pairs) {
                ScopeTabOrderUpdateRequest.OrderItem item = new ScopeTabOrderUpdateRequest.OrderItem();
                item.setScopeId(p[0]);
                item.setSortOrder((int) p[1]);
                items.add(item);
            }
            r.setOrders(items);
            return r;
        }

        @Test
        @DisplayName("全件所属・sortOrder一意なら UPSERT + 監査ログ")
        void success() {
            given(accessControlService.isMember(eq(USER_ID), anyLong(), eq("TEAM"))).willReturn(true);
            given(scopeTabOrderRepository.findByUserIdAndScopeTypeAndScopeId(eq(USER_ID), eq("TEAM"), anyLong()))
                    .willReturn(Optional.empty());

            service.updateOrder(req("TEAM", new long[][]{{7L, 0}, {12L, 1}, {3L, 2}}));

            verify(scopeTabOrderRepository).saveAll(any());
            verify(auditLogService).record(eq("DASHBOARD_SCOPE_TAB_ORDER_UPDATED"),
                    eq(USER_ID), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("非所属が1件でも混入したら全体 SCOPE_TAB_001（UPSERTしない）")
        void nonMemberRejectsAll() {
            given(accessControlService.isMember(eq(USER_ID), eq(7L), eq("TEAM"))).willReturn(true);
            given(accessControlService.isMember(eq(USER_ID), eq(99L), eq("TEAM"))).willReturn(false);

            assertThatThrownBy(() -> service.updateOrder(req("TEAM", new long[][]{{7L, 0}, {99L, 1}})))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("所属していない");

            verify(scopeTabOrderRepository, never()).saveAll(any());
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("sortOrder 重複は SCOPE_TAB_002（所属検証より前に弾く）")
        void duplicateSortOrder() {
            assertThatThrownBy(() -> service.updateOrder(req("TEAM", new long[][]{{7L, 0}, {12L, 0}})))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("表示順");

            verify(accessControlService, never()).isMember(anyLong(), anyLong(), any());
            verify(scopeTabOrderRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("不正な scopeType は SCOPE_TAB_003")
        void invalidScopeType() {
            assertThatThrownBy(() -> service.updateOrder(req("PERSONAL", new long[][]{{7L, 0}})))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("スコープ種別");
        }
    }
}
