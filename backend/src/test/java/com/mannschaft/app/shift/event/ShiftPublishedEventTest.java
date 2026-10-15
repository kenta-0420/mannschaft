package com.mannschaft.app.shift.event;

import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.membership.fanout.TeamFanoutRecipientSource;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftPublishedEvent} および {@link ShiftPublishedNotificationListener} のユニットテスト。
 *
 * <h2>fan-out 抜本改修 Wave-1（AC-5 / AC-6）</h2>
 * <p>シフト公開通知の起点を「per-user 同期展開 + notifyAll」から「耐久ジョブ 1 件 enqueue（O(1)）」へ
 * 載せ替えたことを符号化する。受信者展開は裏ワーカーへ移譲され、本リスナーはスコープ 1 件を enqueue する。</p>
 * <ul>
 *   <li>AC-5: 同期 for ループ（受信者ごと INSERT）が消え、{@link NotificationFanoutJobService#enqueue} が
 *       TEAM スコープで<b>ちょうど 1 回</b>呼ばれる。</li>
 *   <li>AC-6: fan-out enqueue が失敗しても業務処理（AFTER_COMMIT リスナー）へ例外を伝播しない（best-effort）。
 *       同一スケジュール公開の二重発火は決定的な {@code source_event_uuid} により 1 ジョブへ収束する（冪等）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ShiftPublishedEventTest {

    @Mock private NotificationFanoutJobService fanoutJobService;

    @InjectMocks
    private ShiftPublishedNotificationListener listener;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TRIGGERED_BY = 100L;
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 4, 12, 0, 0);
    private static final LocalDateTime PUBLISHED_AT_2 = LocalDateTime.of(2026, 8, 4, 15, 30, 0);

    // =========================================================
    // ShiftPublishedEvent フィールド検証
    // =========================================================

    @Nested
    @DisplayName("ShiftPublishedEvent")
    class EventFields {

        @Test
        @DisplayName("コンストラクタで渡した値をすべて保持する")
        void フィールド保持() {
            ShiftPublishedEvent event = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY, PUBLISHED_AT);

            org.assertj.core.api.Assertions.assertThat(event.getScheduleId()).isEqualTo(SCHEDULE_ID);
            org.assertj.core.api.Assertions.assertThat(event.getTeamId()).isEqualTo(TEAM_ID);
            org.assertj.core.api.Assertions.assertThat(event.getTriggeredByUserId()).isEqualTo(TRIGGERED_BY);
            org.assertj.core.api.Assertions.assertThat(event.getPublishedAt()).isEqualTo(PUBLISHED_AT);
            org.assertj.core.api.Assertions.assertThat(event.getOccurredAt()).isNotNull();
        }
    }

    // =========================================================
    // ShiftPublishedNotificationListener（fan-out 載せ替え）
    // =========================================================

    @Nested
    @DisplayName("ShiftPublishedNotificationListener")
    class ListenerTests {

        @Test
        @DisplayName("AC-5: 受信者を展開せず TEAM スコープの耐久ジョブを 1 件だけ enqueue する")
        void 耐久ジョブを1件enqueue() {
            ShiftPublishedEvent event = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY, PUBLISHED_AT);

            listener.onShiftPublished(event);

            // 受信者数に依らずジョブ表への enqueue はちょうど 1 回（O(1)・同期 for ループは消失）。
            verify(fanoutJobService, times(1)).enqueue(
                    eq(TeamFanoutRecipientSource.SCOPE_TYPE),      // 戦略キー: TEAM
                    eq(String.valueOf(TEAM_ID)),                   // scope_ref: チーム ID 文字列
                    eq("SHIFT_PUBLISHED"),
                    any(UUID.class),                               // 冪等キー
                    isNull(),                                      // organizationId: org 非依存
                    // Issue #2871: シフト公開は可変部分が無く全文がアプリの文言。
                    // 文面種別だけを渡し、引数は空配列。
                    eq(FanoutMessageKind.SHIFT_PUBLISHED), eq(new String[0]),
                    eq(NotificationPriority.NORMAL),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq("/shifts/schedules/" + SCHEDULE_ID),
                    eq(TRIGGERED_BY));
        }

        @Test
        @DisplayName("AC-6: enqueue 失敗でも業務処理へ例外を伝播しない（best-effort）")
        void enqueue失敗はbestEffortで握る() {
            ShiftPublishedEvent event = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY, PUBLISHED_AT);
            willThrow(new RuntimeException("fan-out 一時障害"))
                    .given(fanoutJobService).enqueue(
                            any(), any(), any(), any(), any(),any(FanoutMessageKind.class), any(String[].class), any(), any(), any(), any(), any());

            // 状態遷移は既に確定済み。fan-out 失敗はここで握られ、リスナーの外へは伝播しない。
            assertThatCode(() -> listener.onShiftPublished(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-6: 同一公開の二重発火（同一 publishedAt）は同一 UUID を運ぶ（DB uk で 1 ジョブに収束）")
        void 二重発火は同一冪等キー() {
            // 同一 publishedAt の二重発火（同一 AFTER_COMMIT の再送等）。
            ShiftPublishedEvent event = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY, PUBLISHED_AT);

            listener.onShiftPublished(event);
            listener.onShiftPublished(event);

            ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(fanoutJobService, times(2)).enqueue(
                    any(), any(), any(), uuidCaptor.capture(), any(),any(FanoutMessageKind.class), any(String[].class), any(), any(), any(), any(), any());

            // 二度の発火が同一 source_event_uuid を運ぶ → uk_fanout_idempotency で 1 ジョブに収束する。
            org.assertj.core.api.Assertions.assertThat(uuidCaptor.getAllValues())
                    .hasSize(2)
                    .allMatch(uuidCaptor.getAllValues().get(0)::equals);
        }

        @Test
        @DisplayName("AC-6: 再公開（別 publishedAt）は別 UUID を運ぶ（新ジョブで再通知・恒久抑止しない）")
        void 再公開は別冪等キー() {
            // PUBLISHED → ADJUSTING → 再 PUBLISHED。同一 scheduleId だが publishedAt が更新される。
            ShiftPublishedEvent first = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY, PUBLISHED_AT);
            ShiftPublishedEvent republish = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY, PUBLISHED_AT_2);

            listener.onShiftPublished(first);
            listener.onShiftPublished(republish);

            ArgumentCaptor<UUID> uuidCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(fanoutJobService, times(2)).enqueue(
                    any(), any(), any(), uuidCaptor.capture(), any(),any(FanoutMessageKind.class), any(String[].class), any(), any(), any(), any(), any());

            // 別 publishedAt ゆえ別 UUID → uk_fanout_idempotency に衝突せず新ジョブが立ち再通知される。
            org.assertj.core.api.Assertions.assertThat(uuidCaptor.getAllValues())
                    .hasSize(2)
                    .doesNotHaveDuplicates();
        }
    }
}
