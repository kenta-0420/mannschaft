package com.mannschaft.app.notification.credit;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPackageEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.entity.NotificationMonthlyUsageEntity;
import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.error.NotificationCreditErrorCode;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPackageRepository;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.repository.NotificationMonthlyUsageRepository;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationCreditService} の単体テスト。
 * クレジット消費ロジック・残高操作・アラート判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationCreditService 単体テスト")
class NotificationCreditServiceTest {

    @Mock
    private OrganizationNotificationBalanceRepository balanceRepository;

    @Mock
    private NotificationCreditPurchaseRepository purchaseRepository;

    @Mock
    private NotificationCreditPackageRepository packageRepository;

    @Mock
    private NotificationMonthlyUsageRepository monthlyUsageRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private NotificationCreditService service;

    // ─────────────────────────────────────────────────────────
    // consume()
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("consume() - クレジット消費ロジック")
    class ConsumeTests {

        /**
         * 無料枠内で送信可能なケース。クレジットは消費しない。
         */
        @Test
        @DisplayName("無料枠内で送信可能: freeUsedThisMonth += recipientCount")
        void consume_withinFreeQuota() {
            // given
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            OrganizationNotificationBalanceEntity balance = OrganizationNotificationBalanceEntity.builder()
                    .organizationId(1L)
                    .freeUsedThisMonth(0L)
                    .freeQuotaMonth(firstOfMonth)
                    .creditBalance(0L)
                    .gracePeriodDebt(0L)
                    .build();

            given(balanceRepository.findByOrganizationIdForUpdate(1L))
                    .willReturn(Optional.of(balance));
            given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(monthlyUsageRepository.findByOrganizationIdAndMonthAndSourceType(any(), any(), any()))
                    .willReturn(Optional.empty());
            given(monthlyUsageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            service.consume(1L, 100, NotificationSourceType.NOTIFY_ALL);

            // then
            assertThat(balance.getFreeUsedThisMonth()).isEqualTo(100L);
            assertThat(balance.getCreditBalance()).isEqualTo(0L);
        }

        /**
         * 9000通到達でアラートフラグが立つケース。
         */
        @Test
        @DisplayName("9000通超過でアラートフラグが立つ")
        void consume_alertFlagSetAt9000() {
            // given
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            OrganizationNotificationBalanceEntity balance = OrganizationNotificationBalanceEntity.builder()
                    .organizationId(1L)
                    .freeUsedThisMonth(8900L)
                    .freeQuotaMonth(firstOfMonth)
                    .alertSentThisMonth(false)
                    .creditBalance(1000L)
                    .gracePeriodDebt(0L)
                    .build();

            given(balanceRepository.findByOrganizationIdForUpdate(1L))
                    .willReturn(Optional.of(balance));
            given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(monthlyUsageRepository.findByOrganizationIdAndMonthAndSourceType(any(), any(), any()))
                    .willReturn(Optional.empty());
            given(monthlyUsageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(userRoleRepository.findAdminUserIdsByOrganizationId(anyLong()))
                    .willReturn(java.util.List.of());

            // when
            service.consume(1L, 200, NotificationSourceType.NOTIFY_ALL);

            // then: 9100通 >= 9000 → アラートフラグが立つ
            assertThat(balance.getFreeUsedThisMonth()).isEqualTo(9100L);
            assertThat(balance.getAlertSentThisMonth()).isTrue();
        }

        /**
         * クレジット残高から消費されるケース。
         */
        @Test
        @DisplayName("クレジット残高から消費される")
        void consume_creditDeducted() {
            // given
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            OrganizationNotificationBalanceEntity balance = OrganizationNotificationBalanceEntity.builder()
                    .organizationId(1L)
                    .freeUsedThisMonth(10000L) // 無料枠使い切り
                    .freeQuotaMonth(firstOfMonth)
                    .creditBalance(5000L)
                    .gracePeriodDebt(0L)
                    .build();

            given(balanceRepository.findByOrganizationIdForUpdate(1L))
                    .willReturn(Optional.of(balance));
            given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(monthlyUsageRepository.findByOrganizationIdAndMonthAndSourceType(any(), any(), any()))
                    .willReturn(Optional.empty());
            given(monthlyUsageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            service.consume(1L, 500, NotificationSourceType.DIRECT_MAIL);

            // then
            assertThat(balance.getCreditBalance()).isEqualTo(4500L);
        }

        /**
         * 残高不足で猶予期間が開始するケース。
         */
        @Test
        @DisplayName("残高不足で猶予期間が開始する")
        void consume_gracePeriodStarted() {
            // given
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            OrganizationNotificationBalanceEntity balance = OrganizationNotificationBalanceEntity.builder()
                    .organizationId(1L)
                    .freeUsedThisMonth(10000L) // 無料枠使い切り
                    .freeQuotaMonth(firstOfMonth)
                    .creditBalance(0L)          // クレジット残高ゼロ
                    .gracePeriodDebt(0L)
                    .build();

            given(balanceRepository.findByOrganizationIdForUpdate(1L))
                    .willReturn(Optional.of(balance));
            given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(monthlyUsageRepository.findByOrganizationIdAndMonthAndSourceType(any(), any(), any()))
                    .willReturn(Optional.empty());
            given(monthlyUsageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            service.consume(1L, 100, NotificationSourceType.CONFIRMABLE);

            // then: 猶予期間が開始する
            assertThat(balance.getGracePeriodStartAt()).isNotNull();
            assertThat(balance.getGracePeriodDebt()).isEqualTo(100L);
        }

        /**
         * 猶予期間72時間超過で BusinessException が投げられるケース。
         */
        @Test
        @DisplayName("猶予期間72時間超過でBusinessException")
        void consume_gracePeriodExpiredThrows() {
            // given
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            OrganizationNotificationBalanceEntity balance = OrganizationNotificationBalanceEntity.builder()
                    .organizationId(1L)
                    .freeUsedThisMonth(10000L)
                    .freeQuotaMonth(firstOfMonth)
                    .creditBalance(0L)
                    // 猶予期間を73時間前に設定（超過）
                    .gracePeriodStartAt(LocalDateTime.now().minusHours(73))
                    .gracePeriodDebt(200L)
                    .build();

            given(balanceRepository.findByOrganizationIdForUpdate(1L))
                    .willReturn(Optional.of(balance));
            given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when / then
            assertThatThrownBy(() -> service.consume(1L, 100, NotificationSourceType.NOTIFY_ALL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(NotificationCreditErrorCode.CREDIT_INSUFFICIENT));
        }

        /**
         * free_quota_month が古い場合は無料枠がリセットされるケース。
         */
        @Test
        @DisplayName("free_quota_month が古い場合リセットされる")
        void consume_freeQuotaMonthResetWhenOld() {
            // given
            LocalDate lastMonth = LocalDate.now().minusMonths(1).withDayOfMonth(1);
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            OrganizationNotificationBalanceEntity balance = OrganizationNotificationBalanceEntity.builder()
                    .organizationId(1L)
                    .freeUsedThisMonth(5000L)   // 先月の使用量
                    .freeQuotaMonth(lastMonth)   // 先月
                    .creditBalance(0L)
                    .gracePeriodDebt(0L)
                    .build();

            given(balanceRepository.findByOrganizationIdForUpdate(1L))
                    .willReturn(Optional.of(balance));
            given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(monthlyUsageRepository.findByOrganizationIdAndMonthAndSourceType(any(), any(), any()))
                    .willReturn(Optional.empty());
            given(monthlyUsageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            service.consume(1L, 100, NotificationSourceType.NOTIFY_ALL);

            // then: 無料枠がリセットされ、今月分として100通使用される
            // balance.save() が2回呼ばれる（1回目: 月次リセット、2回目: 消費後の最終状態保存）
            ArgumentCaptor<OrganizationNotificationBalanceEntity> captor =
                    ArgumentCaptor.forClass(OrganizationNotificationBalanceEntity.class);
            verify(balanceRepository, times(2)).save(captor.capture());

            // 回帰: リセット分岐で toBuilder().build() の別インスタンスを save していない。
            // 取得した managed entity を直接ミューテートして save に渡している（=同一行 UPDATE）。
            // 別インスタンスなら id=null の新規行 INSERT になり organization_id 一意制約違反で 500。
            assertThat(captor.getAllValues()).allMatch(saved -> saved == balance);

            // リセット後に今月分として 100 通が消費されている（先月分 5000 はクリア済み）
            assertThat(balance.getFreeQuotaMonth()).isEqualTo(firstOfMonth);
            assertThat(balance.getFreeUsedThisMonth()).isEqualTo(100L);
            assertThat(balance.getAlertSentThisMonth()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────
    // addCredits()
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addCredits() - クレジット加算")
    class AddCreditsTests {

        /**
         * 購入完了時に残高に加算されるケース。
         */
        @Test
        @DisplayName("addCredits: 残高に加算される")
        void addCredits_balanceIncreased() {
            // given
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            NotificationCreditPurchaseEntity purchase = NotificationCreditPurchaseEntity.builder()
                    .organizationId(1L)
                    .packageId(1L)
                    .purchasedByUserId(10L)
                    .creditsGranted(100000L)
                    .remainingCredits(100000L)
                    .priceJpy(BigDecimal.valueOf(100000))
                    .paymentStatus(NotificationCreditPurchaseStatus.PAID)
                    .build();

            OrganizationNotificationBalanceEntity balance = OrganizationNotificationBalanceEntity.builder()
                    .organizationId(1L)
                    .freeUsedThisMonth(0L)
                    .freeQuotaMonth(firstOfMonth)
                    .creditBalance(50000L)
                    .gracePeriodDebt(0L)
                    .build();

            given(purchaseRepository.findById(anyLong())).willReturn(Optional.of(purchase));
            given(balanceRepository.findByOrganizationIdForUpdate(1L))
                    .willReturn(Optional.of(balance));
            given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            service.addCredits(1L);

            // then
            assertThat(balance.getCreditBalance()).isEqualTo(150000L);
        }

        /**
         * 購入レコードが見つからない場合は BusinessException が投げられるケース。
         */
        @Test
        @DisplayName("addCredits: 購入レコードなしで BusinessException")
        void addCredits_purchaseNotFoundThrows() {
            // given
            given(purchaseRepository.findById(anyLong())).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.addCredits(999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(NotificationCreditErrorCode.PURCHASE_NOT_FOUND));
        }
    }
}
