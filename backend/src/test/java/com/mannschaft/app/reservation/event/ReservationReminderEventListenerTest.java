package com.mannschaft.app.reservation.event;

import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.service.ReservationPolicyService;
import com.mannschaft.app.reservation.service.ReservationReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReservationReminderEventListener} の単体テスト（F03.4 ⑥ CONFIRMED 時リマインド自動生成）。
 *
 * <p>{@code @TransactionalEventListener} / {@code @Transactional(REQUIRES_NEW)} は Spring プロキシ経由で
 * 初めて有効化されるため、純 Mockito 単体テストでは逆算ロジック本体（CSV パース・過去スキップ・
 * 上限委譲・例外の安全弁）を評価する。アノテーション設定不備（REQUIRES_NEW 欠落等）の起動時バリデーションは
 * 別途 {@link ReservationReminderEventListenerContextTest} で context を実起動して検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationReminderEventListener")
class ReservationReminderEventListenerTest {

    private static final Long TEAM_ID = 700L;
    private static final Long RESERVATION_ID = 9100L;
    private static final Long ACTOR_USER_ID = 42L;
    private static final String SLOT_TITLE = "コートA";

    /**
     * 固定現在時刻: 2026-06-18T10:00。
     *
     * <p>Issue #2526 是正済み判定: {@code onReservationConfirmed} は slotStartAt（業務ローカル時刻）
     * 基準で remindAt を逆算するため、比較対象の now も
     * {@code LocalDateTime.now(clock.withZone(ZoneId.systemDefault()))} で求める。
     * この Clock が表す瞬間は「JVM 既定ゾーンで解釈すると {@link #NOW} になる」ものである必要があり、
     * かつての {@code NOW.toInstant(ZoneOffset.UTC)} は UTC 基準比較というバグ実装を固定していた
     * （実行環境の既定ゾーンが UTC でない場合に破綻する）。{@link ZoneId#systemDefault()} 経由で
     * instant 化することで、実行環境に関わらず {@link #NOW} が正しく渡るようにする。
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 18, 10, 0);
    private final Clock fixedClock =
            Clock.fixed(NOW.atZone(java.time.ZoneId.systemDefault()).toInstant(), ZoneOffset.UTC);

    @Mock
    private ReservationPolicyService policyService;

    @Mock
    private ReservationReminderService reminderService;

    private ReservationReminderEventListener listener;

    private ReservationConfirmedEvent eventWithSlotStart(LocalDateTime slotStartAt) {
        return new ReservationConfirmedEvent(TEAM_ID, RESERVATION_ID, ACTOR_USER_ID, slotStartAt, SLOT_TITLE);
    }

    private void givenPolicy(String remindBeforeHours) {
        ReservationPolicyEntity policy = ReservationPolicyEntity.builder()
                .teamId(TEAM_ID)
                .remindBeforeHours(remindBeforeHours)
                .build();
        when(policyService.getOrDefault(TEAM_ID)).thenReturn(policy);
    }

    private void initListener() {
        listener = new ReservationReminderEventListener(policyService, reminderService, fixedClock);
    }

    private void initListener(Clock clock) {
        listener = new ReservationReminderEventListener(policyService, reminderService, clock);
    }

    @Nested
    @DisplayName("onReservationConfirmed（リマインド時刻の逆算）")
    class OnReservationConfirmed {

        @Test
        @DisplayName("remind_before_hours='24,1' のとき slotStartAt の24h前/1h前の2件を生成する")
        void csv24And1_generatesTwoReminders() {
            // slotStartAt = NOW + 48h => 24h前(NOW+24h)・1h前(NOW+47h) ともに未来
            LocalDateTime slotStartAt = NOW.plusHours(48);
            givenPolicy("24,1");
            initListener();

            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));

            ArgumentCaptor<List<LocalDateTime>> captor = ArgumentCaptor.captor();
            verify(reminderService).generateReminders(eq(RESERVATION_ID), captor.capture());
            assertThat(captor.getValue())
                    .containsExactly(slotStartAt.minusHours(24), slotStartAt.minusHours(1));
        }

        @Test
        @DisplayName("remindAt が現在時刻より過去のものはスキップする")
        void pastRemindAt_skipped() {
            // slotStartAt = NOW + 10h => 24h前(NOW-14h)は過去でスキップ、1h前(NOW+9h)のみ未来
            LocalDateTime slotStartAt = NOW.plusHours(10);
            givenPolicy("24,1");
            initListener();

            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));

            ArgumentCaptor<List<LocalDateTime>> captor = ArgumentCaptor.captor();
            verify(reminderService).generateReminders(eq(RESERVATION_ID), captor.capture());
            assertThat(captor.getValue()).containsExactly(slotStartAt.minusHours(1));
        }

        @Test
        @DisplayName("全リマインド時刻が過去なら generateReminders を呼ばない")
        void allPast_noGenerateCall() {
            // slotStartAt = NOW（直後）=> 24h前/1h前ともに過去
            LocalDateTime slotStartAt = NOW;
            givenPolicy("24,1");
            initListener();

            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));

            verify(reminderService, never()).generateReminders(eq(RESERVATION_ID), anyList());
        }

        @Test
        @DisplayName("固定 Clock の境界: remindAt == now はスキップ（isAfter のみ未来扱い）")
        void boundaryEqualNow_skipped() {
            // 1h前がちょうど NOW になるよう slotStartAt = NOW + 1h（24,1 のうち24h前は過去、1h前は==NOW）
            LocalDateTime slotStartAt = NOW.plusHours(1);
            givenPolicy("24,1");
            initListener();

            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));

            // 24h前=過去, 1h前=NOWちょうど(=未来でない) => 生成対象なし
            verify(reminderService, never()).generateReminders(eq(RESERVATION_ID), anyList());
        }

        @Test
        @DisplayName("3件を超える remind_before_hours でも未来分すべてを Service に渡す（上限は Service が担保）")
        void manyHours_allFutureForwardedToService() {
            // '72,48,24,1' すべて slotStartAt(NOW+100h)より見て未来
            LocalDateTime slotStartAt = NOW.plusHours(100);
            givenPolicy("72,48,24,1");
            initListener();

            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));

            ArgumentCaptor<List<LocalDateTime>> captor = ArgumentCaptor.captor();
            verify(reminderService).generateReminders(eq(RESERVATION_ID), captor.capture());
            assertThat(captor.getValue()).hasSize(4)
                    .containsExactly(
                            slotStartAt.minusHours(72),
                            slotStartAt.minusHours(48),
                            slotStartAt.minusHours(24),
                            slotStartAt.minusHours(1));
        }

        @Test
        @DisplayName("不正トークン・0以下は無視して正整数のみパースする")
        void invalidTokens_ignored() {
            LocalDateTime slotStartAt = NOW.plusHours(100);
            givenPolicy("24, abc, 0, -3 , 1");
            initListener();

            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));

            ArgumentCaptor<List<LocalDateTime>> captor = ArgumentCaptor.captor();
            verify(reminderService).generateReminders(eq(RESERVATION_ID), captor.capture());
            assertThat(captor.getValue())
                    .containsExactly(slotStartAt.minusHours(24), slotStartAt.minusHours(1));
        }

        @Test
        @DisplayName("remind_before_hours が空なら generateReminders を呼ばない")
        void blankCsv_noGenerateCall() {
            LocalDateTime slotStartAt = NOW.plusHours(48);
            givenPolicy("   ");
            initListener();

            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));

            verify(reminderService, never()).generateReminders(eq(RESERVATION_ID), anyList());
        }

        @Test
        @DisplayName(
                "Issue #2526 番人: 過去判定は Clock のゾーンに左右されず、同一瞬間なら結果が一致する")
        void 過去判定はClockのゾーンに左右されない() {
            // slotStartAt は業務ローカル時刻。「業務基準（JVM 既定ゾーン。実行環境に依存し得るため
            // 決め打ちしない）で見て slotStartAt の 1h 前より 1 分未来」を、実際の JVM 既定ゾーンで
            // instant 化した「同一瞬間」を、ゾーン設定だけが異なる 2 つの Clock（UTC / Asia+09:00）
            // で表現する。正しい実装なら Clock 自身のゾーンに左右されず、
            // どちらも同じ remindAtList（1h前のみ・24h前は過去でスキップ）を生成するはずである。
            LocalDateTime slotStartAt = LocalDateTime.of(2026, 6, 20, 12, 0);
            Instant sameInstant = slotStartAt.minusHours(1).plusMinutes(1)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
            givenPolicy("24,1");

            initListener(Clock.fixed(sameInstant, ZoneOffset.UTC));
            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));
            ArgumentCaptor<List<LocalDateTime>> captorUtc = ArgumentCaptor.captor();
            verify(reminderService).generateReminders(eq(RESERVATION_ID), captorUtc.capture());

            initListener(Clock.fixed(sameInstant, java.time.ZoneId.of("Asia/Tokyo")));
            listener.onReservationConfirmed(eventWithSlotStart(slotStartAt));
            ArgumentCaptor<List<LocalDateTime>> captorTokyo = ArgumentCaptor.captor();
            verify(reminderService, org.mockito.Mockito.times(2))
                    .generateReminders(eq(RESERVATION_ID), captorTokyo.capture());

            assertThat(captorUtc.getValue())
                    .as("UTC Clock: 24h前は過去でスキップ・1h前のみ未来のはず")
                    .containsExactly(slotStartAt.minusHours(1));
            assertThat(captorTokyo.getAllValues().get(1))
                    .as("Clock のゾーン設定が判定結果に漏れ出してはならない")
                    .isEqualTo(captorUtc.getValue());
        }
    }

    @Nested
    @DisplayName("安全弁（AFTER_COMMIT 副作用失敗を確定TXへ波及させない）")
    class SafetyValve {

        @Test
        @DisplayName("generateReminders が例外を投げてもリスナーは例外を伝播しない")
        void generateThrows_swallowedAndLogged() {
            LocalDateTime slotStartAt = NOW.plusHours(48);
            givenPolicy("24,1");
            doThrow(new RuntimeException("DB 障害")).when(reminderService)
                    .generateReminders(eq(RESERVATION_ID), anyList());
            initListener();

            assertThatCode(() -> listener.onReservationConfirmed(eventWithSlotStart(slotStartAt)))
                    .doesNotThrowAnyException();
        }
    }
}
