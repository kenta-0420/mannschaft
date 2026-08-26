package com.mannschaft.app.inbox.batch;

import com.mannschaft.app.inbox.InboxNotificationTypes;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F04.11 Phase3 ② {@link InboxSnoozeRevivalBatchService} 単体テスト（Mockito）。
 *
 * <p>設計書 03_business_logic.md §5（スヌーズ自動復帰の push 再通知）の受け入れ条件:
 * <ul>
 *   <li>復帰期限到来かつ未通知かつ非アーカイブの行を横断取得し、各行へ push を送る</li>
 *   <li>push 送信後に {@code snooze_notified_at} をセットして保存する（1 度だけ）</li>
 *   <li>2 回目の実行では同じ行が取得されない（冪等＝再送しない）</li>
 *   <li>push は専用種別 {@code INBOX_SNOOZE_REVIVAL} で発行する（受信箱への自己増殖回避）</li>
 *   <li>対象なしのときは何も送らない</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InboxSnoozeRevivalBatchService 単体テスト")
class InboxSnoozeRevivalBatchServiceTest {

    @Mock
    private InboxItemStateRepository itemStateRepository;

    @Mock
    private NotificationHelper notificationHelper;

    /** Issue #2715 CMP-055 lot C-5/C-6: newly added i18n dependencies. */
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private InboxSnoozeRevivalBatchService batchService;

    /**
     * Issue #2715 CMP-055 lot C-5/C-6: the bare MessageSource mock would return null for
     * title/body. Return the supplied default message so existing assertions keep working.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubI18nMessageSource() {
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private InboxItemStateEntity dueRow(Long userId, InboxSourceType type, Long sourceId) {
        InboxItemStateEntity e = new InboxItemStateEntity();
        e.setUserId(userId);
        e.setSourceType(type);
        e.setSourceId(sourceId);
        e.setSnoozedUntil(LocalDateTime.now().minusMinutes(1)); // 復帰期限到来済み
        e.setSnoozeNotifiedAt(null);                            // 未通知
        e.setArchivedAt(null);                                  // 非アーカイブ
        return e;
    }

    @Test
    @DisplayName("正常系: 復帰期限到来行へ push を送り、snooze_notified_at をセットして保存する")
    void due_sendsPushAndStampsNotifiedAt() {
        InboxItemStateEntity row = dueRow(10L, InboxSourceType.NOTIFICATION, 123L);
        given(itemStateRepository.findDueForRevival(any(LocalDateTime.class), any()))
                .willReturn(List.of(row));
        given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batchService.run();

        // push が 1 回呼ばれる
        verify(notificationHelper, times(1)).notify(
                eq(10L),
                eq(InboxNotificationTypes.INBOX_SNOOZE_REVIVAL),
                anyString(), anyString(),
                anyString(), isNull(),
                eq(NotificationScopeType.PERSONAL), isNull(),
                anyString(), isNull());

        // snooze_notified_at がセットされ保存される
        ArgumentCaptor<InboxItemStateEntity> captor = ArgumentCaptor.forClass(InboxItemStateEntity.class);
        verify(itemStateRepository).save(captor.capture());
        assertThat(captor.getValue().getSnoozeNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("冪等: 2 回目の実行では取得対象が無く（notified 済みで除外）、push も保存もしない")
    void idempotent_secondRunSendsNothing() {
        // 1 回目で snooze_notified_at がセットされたため、クエリ条件
        // snooze_notified_at IS NULL に該当せず 2 回目は空が返る前提。
        given(itemStateRepository.findDueForRevival(any(LocalDateTime.class), any()))
                .willReturn(List.of());

        batchService.run();

        verify(notificationHelper, never()).notify(
                any(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any());
        verify(itemStateRepository, never()).save(any());
    }

    @Test
    @DisplayName("正常系: 複数行はそれぞれ push＋stamp される")
    void multipleRows_eachNotifiedAndStamped() {
        InboxItemStateEntity r1 = dueRow(1L, InboxSourceType.NOTIFICATION, 100L);
        InboxItemStateEntity r2 = dueRow(2L, InboxSourceType.TODO_DUE, 200L);
        given(itemStateRepository.findDueForRevival(any(LocalDateTime.class), any()))
                .willReturn(List.of(r1, r2));
        given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batchService.run();

        verify(notificationHelper, times(2)).notify(
                any(), eq(InboxNotificationTypes.INBOX_SNOOZE_REVIVAL),
                anyString(), anyString(), anyString(), isNull(),
                eq(NotificationScopeType.PERSONAL), isNull(), anyString(), isNull());
        verify(itemStateRepository, times(2)).save(any());
        assertThat(r1.getSnoozeNotifiedAt()).isNotNull();
        assertThat(r2.getSnoozeNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("対象なし: push も保存もしない")
    void noDueRows_doesNothing() {
        given(itemStateRepository.findDueForRevival(any(LocalDateTime.class), any()))
                .willReturn(List.of());

        batchService.run();

        verify(notificationHelper, never()).notify(
                any(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any());
        verify(itemStateRepository, never()).save(any());
    }

    @Test
    @DisplayName("ベストエフォート1回(最重要): push が例外でも snooze_notified_at を刻んで保存する（無限再試行を根絶＝失敗行も再送しない）")
    void failure_stillStampsNotifiedAt_bestEffortOnce() {
        // 新仕様（案C）: push の成否に関わらず一度きり stamp する。
        // 旧仕様は「失敗行は stamp せず次回バッチで再試行」だったが、これは 5 分毎の無限再試行を招くため反転。
        // 恒久失敗のサブスク失効掃除は WebPushService の 410/404 deleteByEndpoint に委譲する。
        InboxItemStateEntity row = dueRow(10L, InboxSourceType.NOTIFICATION, 123L);
        given(itemStateRepository.findDueForRevival(any(LocalDateTime.class), any()))
                .willReturn(List.of(row));
        given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        // push が常に失敗する
        org.mockito.BDDMockito.willThrow(new RuntimeException("push failed"))
                .given(notificationHelper).notify(
                        eq(10L), anyString(), anyString(), anyString(),
                        anyString(), isNull(), any(), isNull(), anyString(), isNull());

        batchService.run();

        // 失敗しても stamp して保存する（＝次回バッチで再試行しない）
        ArgumentCaptor<InboxItemStateEntity> captor = ArgumentCaptor.forClass(InboxItemStateEntity.class);
        verify(itemStateRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getSnoozeNotifiedAt())
                .as("push 失敗でも best-effort 1 回として stamp する（無限再試行の根絶）")
                .isNotNull();
        assertThat(row.getSnoozeNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("継続性: 1 件の push が例外でも残りの行を処理し、両行とも stamp される（best-effort 1回）")
    void oneFailure_continuesOthers_bothStamped() {
        InboxItemStateEntity r1 = dueRow(1L, InboxSourceType.NOTIFICATION, 100L);
        InboxItemStateEntity r2 = dueRow(2L, InboxSourceType.NOTIFICATION, 200L);
        given(itemStateRepository.findDueForRevival(any(LocalDateTime.class), any()))
                .willReturn(List.of(r1, r2));
        given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        // r1（userId=1）の push で例外、r2 は成功
        org.mockito.BDDMockito.willThrow(new RuntimeException("push failed"))
                .given(notificationHelper).notify(
                        eq(1L), anyString(), anyString(), anyString(),
                        anyString(), isNull(), any(), isNull(), anyString(), isNull());

        batchService.run();

        // r2 は push＋stamp される
        verify(notificationHelper).notify(
                eq(2L), anyString(), anyString(), anyString(),
                anyString(), isNull(), any(), isNull(), anyString(), isNull());
        // 新仕様: 失敗した r1 も stamp される（best-effort 1回・再試行しない）
        assertThat(r1.getSnoozeNotifiedAt()).isNotNull();
        assertThat(r2.getSnoozeNotifiedAt()).isNotNull();
        // 両行とも save される
        verify(itemStateRepository, times(2)).save(any());
    }
}
