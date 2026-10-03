package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.dashboard.dto.AdminActionRequiredResponse;
import com.mannschaft.app.matching.service.MatchingAdminQueryService;
import com.mannschaft.app.payment.service.PaymentAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminQueryService;
import com.mannschaft.app.shift.service.ShiftRequestAdminQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F10.1.1 / P1: {@link AdminActionRequiredFacade} の単体テスト（純 Mockito）。
 *
 * <p>設計書 03 §8 の観点を検証する: スコープ別動的ドメイン集合・認可伝播・縮退限定
 * （一時障害のみ degraded・認可/プログラミングエラーは再スロー）・total_pending の縮退非加算。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminActionRequiredFacade 単体テスト")
class AdminActionRequiredFacadeTest {

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ReservationAdminQueryService reservationAdminQueryService;
    @Mock
    private ShiftRequestAdminQueryService shiftRequestAdminQueryService;
    @Mock
    private MatchingAdminQueryService matchingAdminQueryService;
    @Mock
    private PaymentAdminQueryService paymentAdminQueryService;

    @InjectMocks
    private AdminActionRequiredFacade facade;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final String TEAM_SLUG = "dev-team";
    private static final Long ORG_ID = 20L;
    private static final String ORG_SLUG = "dev-org";

    private PendingAggregate agg(long count, int items) {
        List<PendingAggregate.Item> list = new java.util.ArrayList<>();
        for (int i = 0; i < items; i++) {
            // detail_route は Query Service が組み立てる個別遷移先（id を含む）。Facade はこれを透過する。
            list.add(new PendingAggregate.Item(
                    String.valueOf(i), "タイトル" + i, "申請者" + i, LocalDateTime.now(),
                    "/detail/" + i));
        }
        return new PendingAggregate(count, list);
    }

    @Nested
    @DisplayName("スコープ別動的ドメイン集合")
    class DynamicDomains {

        @Test
        @DisplayName("ADMIN/team → 予約/シフト/マッチングのみ（payment 含まない）")
        void team_threeDomainsNoPayment() {
            given(reservationAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(2, 2));
            given(shiftRequestAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(3, 3));
            given(matchingAdminQueryService.pendingReceivedForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(1, 1));

            AdminActionRequiredResponse res =
                    facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3);

            assertThat(res.domains()).extracting(AdminActionRequiredResponse.DomainSection::domain)
                    .containsExactlyInAnyOrder("RESERVATION", "SHIFT_REQUEST", "MATCHING")
                    .doesNotContain("PAYMENT");
            assertThat(res.totalPending()).isEqualTo(6);
            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verifyNoInteractions(paymentAdminQueryService);

            // detail_route は Query Service が返した個別遷移先を透過し、list_route（status 付き一覧）とは別物（§3.1）。
            res.domains().forEach(d -> d.items().forEach(item -> {
                assertThat(item.detailRoute()).isNotEqualTo(d.listRoute());
                assertThat(item.detailRoute()).startsWith("/detail/");
            }));
        }

        @Test
        @DisplayName("ADMIN/org → 未収請求(payment)のみ（team 系含まない）")
        void org_onlyPayment() {
            given(paymentAdminQueryService.unsettledForOrg(eq(ORG_ID), eq(ORG_SLUG), anyInt())).willReturn(agg(4, 3));

            AdminActionRequiredResponse res =
                    facade.getAdminActionRequired(USER_ID, "ORGANIZATION", ORG_ID, ORG_SLUG, 3);

            assertThat(res.domains()).extracting(AdminActionRequiredResponse.DomainSection::domain)
                    .containsExactly("PAYMENT");
            assertThat(res.totalPending()).isEqualTo(4);
            verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            verifyNoInteractions(reservationAdminQueryService, shiftRequestAdminQueryService, matchingAdminQueryService);
        }

        @Test
        @DisplayName("preview_size=0 → items 空・pending_count のみ")
        void previewSizeZero() {
            given(reservationAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), eq(0))).willReturn(agg(2, 0));
            given(shiftRequestAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), eq(0))).willReturn(agg(3, 0));
            given(matchingAdminQueryService.pendingReceivedForTeam(eq(TEAM_ID), eq(TEAM_SLUG), eq(0))).willReturn(agg(1, 0));

            AdminActionRequiredResponse res =
                    facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 0);

            assertThat(res.domains()).allSatisfy(d -> assertThat(d.items()).isEmpty());
            assertThat(res.totalPending()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("認可")
    class Authorization {

        @Test
        @DisplayName("非 ADMIN（checkAdminOrAbove が 403）→ 全体が拒否され Query Service は呼ばれない")
        void nonAdminRejected() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);

            verifyNoInteractions(reservationAdminQueryService, shiftRequestAdminQueryService,
                    matchingAdminQueryService, paymentAdminQueryService);
        }

        @Test
        @DisplayName("Query Service が内部で認可違反(COMMON_002)を投げる → 縮退せず全体が伝播")
        void domainAuthErrorPropagates() {
            // CompletableFuture 並列実行＋短絡伝播のため、shiftRequest が例外を投げると
            // reservation / matching の stub がタイミング次第で消費されない場合がある。
            // UnnecessaryStubbingException（間欠的な flaky）を防ぐため lenient 化する。
            // assert は一切弱めておらず、例外の型・エラーコードは strict に検証する。
            lenient().when(reservationAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).thenReturn(agg(1, 1));
            given(shiftRequestAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt()))
                    .willThrow(new BusinessException(CommonErrorCode.COMMON_002));
            lenient().when(matchingAdminQueryService.pendingReceivedForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).thenReturn(agg(1, 1));

            assertThatThrownBy(() -> facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);
        }
    }

    @Nested
    @DisplayName("縮退（degradation）")
    class Degradation {

        @Test
        @DisplayName("1 ドメインが一時障害(DataAccessException) → 当該のみ degraded・他は正常・total に非加算")
        void transientDegradesOnlyThatDomain() {
            given(reservationAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt()))
                    .willThrow(new DataAccessResourceFailureException("DB 一時障害"));
            given(shiftRequestAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(3, 3));
            given(matchingAdminQueryService.pendingReceivedForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(1, 1));

            AdminActionRequiredResponse res =
                    facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3);

            AdminActionRequiredResponse.DomainSection reservation = res.domains().stream()
                    .filter(d -> "RESERVATION".equals(d.domain())).findFirst().orElseThrow();
            assertThat(reservation.degraded()).isTrue();
            assertThat(reservation.pendingCount()).isZero();
            assertThat(reservation.items()).isEmpty();
            // 他ドメインは正常・total には縮退分(予約)を加算しない → 3 + 1 = 4
            assertThat(res.totalPending()).isEqualTo(4);
        }

        @Test
        @DisplayName("1 ドメインが NullPointerException(プログラミングエラー) → 縮退せず全体が伝播（500 相当）")
        void programmingErrorPropagates() {
            // CompletableFuture 並列実行＋短絡伝播のため、shiftRequest が例外を投げると
            // reservation / matching の stub がタイミング次第で消費されない場合がある。
            // UnnecessaryStubbingException（間欠的な flaky）を防ぐため lenient 化する。
            // assert は一切弱めておらず、NullPointerException の伝播は strict に検証する。
            lenient().when(reservationAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).thenReturn(agg(1, 1));
            given(shiftRequestAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt()))
                    .willThrow(new NullPointerException("バグ"));
            lenient().when(matchingAdminQueryService.pendingReceivedForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).thenReturn(agg(1, 1));

            assertThatThrownBy(() -> facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("不正スコープ")
    class InvalidScope {

        @Test
        @DisplayName("未対応スコープ種別 → IllegalArgumentException（500 相当）")
        void unknownScopeType() {
            assertThatThrownBy(() -> facade.getAdminActionRequired(USER_ID, "PERSONAL", 99L, "x", 3))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(accessControlService).checkAdminOrAbove(USER_ID, 99L, "PERSONAL");
            verify(reservationAdminQueryService, never()).pendingForTeam(eq(99L), any(), anyInt());
        }
    }
}
