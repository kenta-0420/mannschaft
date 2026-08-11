package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.13 {@link NotificationCreditMonthlyResetBatch} のユニットテスト。
 *
 * <p>絞り込み条件の無い全件走査を id 昇順キーセットページングで行うことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationCreditMonthlyResetBatch（月次リセットバッチ）")
class NotificationCreditMonthlyResetBatchTest {

    @Mock private OrganizationNotificationBalanceRepository balanceRepository;
    @Mock private NotificationHelper notificationHelper;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks
    private NotificationCreditMonthlyResetBatch batch;

    private OrganizationNotificationBalanceEntity balance(long id, long organizationId, long gracePeriodDebt) {
        return OrganizationNotificationBalanceEntity.builder()
                .id(id)
                .organizationId(organizationId)
                .freeUsedThisMonth(0L)
                .freeQuotaMonth(LocalDate.of(2026, 7, 1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodDebt(gracePeriodDebt)
                .build();
    }

    @Test
    @DisplayName("正常系: 対象が空の場合は何も処理しない")
    void 正常系_対象なし() {
        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(List.of());

        batch.runBatch();

        verify(balanceRepository, times(0)).save(any());
    }

    @Test
    @DisplayName("正常系: 単一ページの全組織が月次リセットされる")
    void 正常系_単一ページ全件処理() {
        OrganizationNotificationBalanceEntity a = balance(1L, 100L, 0L);
        OrganizationNotificationBalanceEntity b = balance(2L, 200L, 0L);
        // batch.size()(2) < PAGE_SIZE(500) のため、この1ページで打ち切られ次ページは問い合わせない
        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(List.of(a, b));
        given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batch.runBatch();

        assertThat(a.getFreeUsedThisMonth()).isZero();
        assertThat(b.getFreeUsedThisMonth()).isZero();
        verify(balanceRepository).save(a);
        verify(balanceRepository).save(b);
    }

    @Test
    @DisplayName("境界: ページサイズ（500件）をまたぐ全組織が処理される（取りこぼし検出）")
    void 境界_ページサイズをまたぐ全件が処理される() {
        int pageSize = 500;
        int total = pageSize + 1;
        List<OrganizationNotificationBalanceEntity> firstPage = new ArrayList<>();
        for (long id = 1; id <= pageSize; id++) {
            firstPage.add(balance(id, id, 0L));
        }
        OrganizationNotificationBalanceEntity last = balance(total, total, 0L);

        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(firstPage);
        // 2ページ目は batch.size()(1) < PAGE_SIZE(500) のためここで打ち切られ、3ページ目は問い合わせない
        given(balanceRepository.findAllAfterId(eq((long) pageSize), any())).willReturn(List.of(last));
        given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batch.runBatch();

        // 全 501 件が保存され、対象母集合を縮めない走査でカーソルが1ページ目末尾まで前進したことを検証する
        verify(balanceRepository, times(total)).save(any());
        verify(balanceRepository).findAllAfterId(eq((long) pageSize), any());
    }

    @Test
    @DisplayName("安全弁: MAX_PAGES に到達したら打ち切る")
    void 安全弁_MAX_PAGES到達で打ち切り() {
        // 毎回ちょうど PAGE_SIZE 件返し続け、hasNext が尽きない状況を再現する
        given(balanceRepository.findAllAfterId(any(Long.class), any()))
                .willAnswer(inv -> {
                    long cursor = inv.getArgument(0);
                    List<OrganizationNotificationBalanceEntity> page = new ArrayList<>();
                    for (long id = cursor + 1; id <= cursor + NotificationCreditMonthlyResetBatch.PAGE_SIZE; id++) {
                        page.add(balance(id, id, 0L));
                    }
                    return page;
                });
        given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batch.runBatch();

        verify(balanceRepository, times(NotificationCreditMonthlyResetBatch.MAX_PAGES))
                .findAllAfterId(any(Long.class), any());
    }

    @Test
    @DisplayName("負債相殺で残高がマイナスになった組織はADMINアラート対象になる")
    void 負債相殺でマイナス残高はアラート対象() {
        OrganizationNotificationBalanceEntity debtor = OrganizationNotificationBalanceEntity.builder()
                .id(1L)
                .organizationId(100L)
                .freeUsedThisMonth(0L)
                .freeQuotaMonth(LocalDate.of(2026, 7, 1))
                .alertSentThisMonth(false)
                .creditBalance(5L)
                .gracePeriodDebt(10L)
                .build();
        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(List.of(debtor));
        given(balanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batch.runBatch();

        // monthlyReset() 実行後、creditBalance = 5 - 10 = -5 となりアラート対象
        assertThat(debtor.getCreditBalance()).isEqualTo(-5L);
        verify(balanceRepository).save(debtor);
    }
}
