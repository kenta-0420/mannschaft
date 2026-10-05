package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.event.MarketListingFinalizedEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MarketFinalizeService} 単体テスト。
 * 🟠-1 最終認証通知の重複発火ガード（02_api_design §6.1）を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketFinalizeService 単体テスト (F22.1 市)")
class MarketFinalizeServiceTest {

    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private ConfirmableNotificationRepository confirmableNotificationRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MarketFinalizeService service;

    private static final Long LISTING_ID = 500L;
    private static final Long TEAM_ID = 88L;

    /** planFinalizeConfirmation は実TXの内側でしか呼べない（札の行ロックを通知作成まで握るため）。 */
    @BeforeEach
    void enterTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void leaveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("Codex P2: planFinalizeConfirmation を実TXの外から呼ぶと札を読まずに IllegalStateException（行ロックが即座に外れ直列化が成立しないため）")
    void planFinalizeConfirmation_outsideTransaction_rejected() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> service.planFinalizeConfirmation(LISTING_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(LISTING_ID));
        verify(listingRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("Codex P2: 札の状態は PESSIMISTIC_WRITE（findByIdForUpdate）で読む。ロック無しの findById は使わない")
    void planFinalizeConfirmation_readsListingUnderRowLock() throws Exception {
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(fullListing()));
        given(confirmableNotificationRepository.existsBySourceTypeAndSourceIdAndStatus(
                MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE, LISTING_ID, ConfirmableNotificationStatus.ACTIVE))
                .willReturn(true);

        service.planFinalizeConfirmation(LISTING_ID);

        verify(listingRepository).findByIdForUpdate(LISTING_ID);
        verify(listingRepository, never()).findById(anyLong());
    }

    private RecruitmentListingEntity fullListing() throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("11/3 練習試合")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(7))
                .endAt(LocalDateTime.now().plusDays(7).plusHours(2))
                .applicationDeadline(LocalDateTime.now().plusDays(5))
                .autoCancelAt(LocalDateTime.now().plusDays(5))
                .capacity(1)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.PUBLIC)
                .status(RecruitmentListingStatus.FULL)
                .createdBy(7L)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.FULL);
        return listing;
    }

    // CMP-260930-1932: 最終認証通知の送信は申込の業務TXの外（MarketFinalizeConfirmationListener）へ移した。
    // 本サービスは札の最新状態を読み直して「送るか・誰に何を送るか」を決めるだけで、送信はしない。

    @Test
    @DisplayName("未確認(ACTIVE)の MARKET_FINALIZE 通知が既存なら送らない（重複発火ガード）")
    void planFinalizeConfirmation_alreadyPending_empty() throws Exception {
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(fullListing()));
        given(confirmableNotificationRepository.existsBySourceTypeAndSourceIdAndStatus(
                eq(MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE),
                eq(LISTING_ID),
                eq(ConfirmableNotificationStatus.ACTIVE)))
                .willReturn(true);

        assertThat(service.planFinalizeConfirmation(LISTING_ID)).isEmpty();
    }

    @Test
    @DisplayName("未確認通知が無ければ、チーム ADMIN を受信者とする送信内容を返す")
    void planFinalizeConfirmation_noPending_plansTeamAdmins() throws Exception {
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(fullListing()));
        given(confirmableNotificationRepository.existsBySourceTypeAndSourceIdAndStatus(
                eq(MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE),
                eq(LISTING_ID),
                eq(ConfirmableNotificationStatus.ACTIVE)))
                .willReturn(false);
        given(userRoleRepository.findUserIdsByTeamIdAndRoleName(eq(TEAM_ID), eq("ADMIN")))
                .willReturn(List.of(101L, 102L));

        MarketFinalizeService.FinalizeConfirmationPlan plan =
                service.planFinalizeConfirmation(LISTING_ID).orElseThrow();

        assertThat(plan.listingId()).isEqualTo(LISTING_ID);
        assertThat(plan.scopeType()).isEqualTo(ScopeType.TEAM);
        assertThat(plan.scopeId()).isEqualTo(TEAM_ID);
        assertThat(plan.createdByUserId()).isEqualTo(7L);
        assertThat(plan.recipientUserIds()).containsExactly(101L, 102L);
        assertThat(plan.actionUrl()).isEqualTo("/market/listings/" + LISTING_ID);
    }

    @Test
    @DisplayName("PERSONAL札は PLATFORM スコープで札主本人を受信者にする")
    void planFinalizeConfirmation_personal_plansOwner() throws Exception {
        RecruitmentListingEntity listing = fullListing();
        setField(listing, "scopeType", RecruitmentScopeType.PERSONAL);
        setField(listing, "scopeId", 7L);
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
        given(confirmableNotificationRepository.existsBySourceTypeAndSourceIdAndStatus(
                eq(MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE),
                eq(LISTING_ID),
                eq(ConfirmableNotificationStatus.ACTIVE)))
                .willReturn(false);

        MarketFinalizeService.FinalizeConfirmationPlan plan =
                service.planFinalizeConfirmation(LISTING_ID).orElseThrow();

        assertThat(plan.scopeType()).isEqualTo(ScopeType.PLATFORM);
        assertThat(plan.scopeId()).isEqualTo(7L);
        assertThat(plan.createdByUserId()).isEqualTo(7L);
        assertThat(plan.recipientUserIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("最新状態が FULL 以外の札には送らない（コミット時点の状態で判断する）")
    void planFinalizeConfirmation_notFull_empty() throws Exception {
        RecruitmentListingEntity listing = fullListing();
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

        assertThat(service.planFinalizeConfirmation(LISTING_ID)).isEmpty();
        verify(confirmableNotificationRepository, never()).existsBySourceTypeAndSourceIdAndStatus(
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("札が不在（削除済み等）なら送らない")
    void planFinalizeConfirmation_missing_empty() {
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.empty());

        assertThat(service.planFinalizeConfirmation(LISTING_ID)).isEmpty();
    }

    private RecruitmentListingEntity fullListing(boolean paymentEnabled) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("11/3 練習試合")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(7))
                .endAt(LocalDateTime.now().plusDays(7).plusHours(2))
                .applicationDeadline(LocalDateTime.now().plusDays(5))
                .autoCancelAt(LocalDateTime.now().plusDays(5))
                .capacity(1)
                .minCapacity(1)
                .paymentEnabled(paymentEnabled)
                .price(paymentEnabled ? 10_000 : null)
                .visibility(RecruitmentVisibility.PUBLIC)
                .status(RecruitmentListingStatus.FULL)
                .createdBy(7L)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.FULL);
        return listing;
    }

    @Test
    @DisplayName("finalizeBySourceId: 謝礼札（payment_enabled=true）→ COMPLETED 化＋MarketListingFinalizedEvent(paymentEnabled=true) を発火（払出シーム）")
    void finalizeBySourceId_paymentEnabled_publishesEventWithPaymentTrue() throws Exception {
        RecruitmentListingEntity listing = fullListing(true);
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

        service.finalizeBySourceId(LISTING_ID);

        // FULL→COMPLETED 確定後に発火されること（最終認証→払出の最重要シーム）。
        verify(eventPublisher).publishEvent(argThat((Object e) ->
                e instanceof MarketListingFinalizedEvent ev
                        && LISTING_ID.equals(ev.listingId())
                        && ev.paymentEnabled()));
    }

    @Test
    @DisplayName("finalizeBySourceId: 謝礼なし札（payment_enabled=false）→ COMPLETED 化＋MarketListingFinalizedEvent(paymentEnabled=false) を発火（払出側で no-op）")
    void finalizeBySourceId_paymentDisabled_publishesEventWithPaymentFalse() throws Exception {
        RecruitmentListingEntity listing = fullListing(false);
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

        service.finalizeBySourceId(LISTING_ID);

        verify(eventPublisher).publishEvent(argThat((Object e) ->
                e instanceof MarketListingFinalizedEvent ev
                        && LISTING_ID.equals(ev.listingId())
                        && !ev.paymentEnabled()));
    }

    @Test
    @DisplayName("finalizeBySourceId: FULL 以外（先勝ち COMPLETED 等）→ 冪等 no-op（イベント発火しない）")
    void finalizeBySourceId_notFull_noEvent() throws Exception {
        RecruitmentListingEntity listing = fullListing(true);
        setField(listing, "status", RecruitmentListingStatus.COMPLETED);
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

        service.finalizeBySourceId(LISTING_ID);

        verify(eventPublisher, never()).publishEvent(any(MarketListingFinalizedEvent.class));
    }

    @Test
    @DisplayName("finalizeBySourceId: 札不在（削除済み等）→ no-op（イベント発火しない）")
    void finalizeBySourceId_missing_noEvent() {
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.empty());

        service.finalizeBySourceId(LISTING_ID);

        verify(eventPublisher, never()).publishEvent(any(MarketListingFinalizedEvent.class));
    }

    @Test
    @DisplayName("PERSONAL札も最終認証でCOMPLETEDへ遷移する")
    void finalizeBySourceId_personal_finalizesAndPublishesEvent() throws Exception {
        RecruitmentListingEntity listing = fullListing(false);
        setField(listing, "scopeType", RecruitmentScopeType.PERSONAL);
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

        service.finalizeBySourceId(LISTING_ID);

        verify(listingRepository).save(listing);
        verify(eventPublisher).publishEvent(argThat((Object e) ->
                e instanceof MarketListingFinalizedEvent ev
                        && LISTING_ID.equals(ev.listingId())
                        && !ev.paymentEnabled()));
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
