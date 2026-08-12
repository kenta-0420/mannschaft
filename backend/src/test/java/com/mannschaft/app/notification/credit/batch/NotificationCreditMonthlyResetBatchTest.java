package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditAlertSender;
import com.mannschaft.app.notification.credit.service.NotificationCreditResetOutcome;
import com.mannschaft.app.notification.credit.service.NotificationCreditResetRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.13 {@link NotificationCreditMonthlyResetBatch} のユニットテスト。
 *
 * <p>絞り込み条件の無い全件走査を id 昇順キーセットページングで行うことを検証する。
 * 1件分のリセット処理自体は {@link NotificationCreditResetRunner} へ分離済みのため（CMP-035）、
 * ここではページング・{@code resetRunner} への委譲・アラート発火条件・1件失敗時の継続性に絞って検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationCreditMonthlyResetBatch（月次リセットバッチ）")
class NotificationCreditMonthlyResetBatchTest {

    @Mock private OrganizationNotificationBalanceRepository balanceRepository;
    @Mock private NotificationCreditResetRunner resetRunner;
    @Mock private NotificationCreditAlertSender alertSender;

    @InjectMocks
    private NotificationCreditMonthlyResetBatch batch;

    private OrganizationNotificationBalanceEntity balance(long id, long organizationId) {
        return OrganizationNotificationBalanceEntity.builder()
                .id(id)
                .organizationId(organizationId)
                .freeUsedThisMonth(0L)
                .freeQuotaMonth(LocalDate.of(2026, 7, 1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodDebt(0L)
                .build();
    }

    @Test
    @DisplayName("正常系: 対象が空の場合は何も処理しない")
    void 正常系_対象なし() {
        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(List.of());

        batch.runBatch();

        verify(resetRunner, never()).resetOne(any(), any());
    }

    @Test
    @DisplayName("正常系: 単一ページの全組織で resetRunner が呼ばれる")
    void 正常系_単一ページ全件でresetRunnerを呼ぶ() {
        OrganizationNotificationBalanceEntity a = balance(1L, 100L);
        OrganizationNotificationBalanceEntity b = balance(2L, 200L);
        // batch.size()(2) < PAGE_SIZE(500) のため、この1ページで打ち切られ次ページは問い合わせない
        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(List.of(a, b));
        given(resetRunner.resetOne(anyLong(), any()))
                .willReturn(new NotificationCreditResetOutcome(false, null, null));

        batch.runBatch();

        verify(resetRunner).resetOne(eq(1L), any());
        verify(resetRunner).resetOne(eq(2L), any());
        verify(alertSender, never()).sendNegativeBalanceAlert(any(), any());
    }

    @Test
    @DisplayName("境界: ページサイズ（500件）をまたぐ全組織が処理される（取りこぼし検出）")
    void 境界_ページサイズをまたぐ全件が処理される() {
        int pageSize = 500;
        int total = pageSize + 1;
        List<OrganizationNotificationBalanceEntity> firstPage = new ArrayList<>();
        for (long id = 1; id <= pageSize; id++) {
            firstPage.add(balance(id, id));
        }
        OrganizationNotificationBalanceEntity last = balance(total, total);

        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(firstPage);
        // 2ページ目は batch.size()(1) < PAGE_SIZE(500) のためここで打ち切られ、3ページ目は問い合わせない
        given(balanceRepository.findAllAfterId(eq((long) pageSize), any())).willReturn(List.of(last));
        given(resetRunner.resetOne(anyLong(), any()))
                .willReturn(new NotificationCreditResetOutcome(false, null, null));

        batch.runBatch();

        // 全 501 件が resetRunner に渡され、対象母集合を縮めない走査でカーソルが1ページ目末尾まで前進したことを検証する
        verify(resetRunner, times(total)).resetOne(anyLong(), any());
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
                        page.add(balance(id, id));
                    }
                    return page;
                });
        given(resetRunner.resetOne(anyLong(), any()))
                .willReturn(new NotificationCreditResetOutcome(false, null, null));

        batch.runBatch();

        verify(balanceRepository, times(NotificationCreditMonthlyResetBatch.MAX_PAGES))
                .findAllAfterId(any(Long.class), any());
    }

    @Test
    @DisplayName("resetRunner がマイナス残高アラート要と返した組織は alertSender に委譲される")
    void 負債相殺でマイナス残高はアラート対象() {
        OrganizationNotificationBalanceEntity debtor = balance(1L, 100L);
        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(List.of(debtor));
        given(resetRunner.resetOne(eq(1L), any()))
                .willReturn(new NotificationCreditResetOutcome(true, 100L, -5L));

        batch.runBatch();

        verify(alertSender).sendNegativeBalanceAlert(100L, -5L);
    }

    @Test
    @DisplayName("途中の1件が例外を投げても、後続の組織は処理を継続する")
    void 途中の1件が失敗しても後続は処理を継続する() {
        OrganizationNotificationBalanceEntity broken = balance(1L, 100L);
        OrganizationNotificationBalanceEntity ok = balance(2L, 200L);
        given(balanceRepository.findAllAfterId(eq(0L), any())).willReturn(List.of(broken, ok));
        willThrow(new RuntimeException("リセット失敗"))
                .given(resetRunner).resetOne(eq(1L), any());
        given(resetRunner.resetOne(eq(2L), any()))
                .willReturn(new NotificationCreditResetOutcome(false, null, null));

        batch.runBatch();

        verify(resetRunner).resetOne(eq(1L), any());
        verify(resetRunner).resetOne(eq(2L), any());
    }
}
