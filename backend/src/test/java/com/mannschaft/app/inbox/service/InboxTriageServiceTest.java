package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F04.11 {@link InboxTriageService} 単体テスト（Mockito・Repository モック）。
 *
 * <p>設計書 02_api_design.md §3.3 / 03_business_logic.md §5 / 01_data_model.md §2.1 から、
 * snooze/unsnooze/archive/unarchive の upsert・遅延物理削除・過去時刻拒否を受け入れ条件化する。</p>
 *
 * <p>認可（対象通知が本人に可視か）の判定は {@link InboxAccessGuard} に集約されており、
 * 本テストは実物のゲートに {@link InboxItemVisibilityChecker} のモックを与えて検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InboxTriageService 単体テスト")
class InboxTriageServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SOURCE_ID = 123L;
    private static final InboxSourceType SOURCE_TYPE = InboxSourceType.NOTIFICATION;

    @Mock
    private InboxItemStateRepository itemStateRepository;

    @Mock
    private InboxItemVisibilityChecker visibilityChecker;

    @Mock
    private NotificationLabelRepository labelRepository;

    /**
     * 認可ゲートは実物を使う（可視性チェッカーは上のモックを流用する）。
     * 対象通知の可視性判定は {@code visibilityChecker.isVisibleTo} のままなので、
     * 各テストのスタブはそのまま認可判定に効く。
     */
    private InboxTriageService triageService;

    @BeforeEach
    void wireService() {
        triageService = new InboxTriageService(itemStateRepository,
                new InboxAccessGuard(labelRepository, visibilityChecker));
    }

    /** JST(+09:00) オフセット。アプリは JVM 既定 TZ を Asia/Tokyo に固定しているため、これが壁時計の基準。 */
    private static final ZoneOffset JST = ZoneOffset.ofHours(9);

    /** 既存オーバーレイ行を生成するヘルパー。 */
    private InboxItemStateEntity existing(LocalDateTime snoozedUntil, LocalDateTime archivedAt) {
        InboxItemStateEntity e = new InboxItemStateEntity();
        e.setUserId(USER_ID);
        e.setSourceType(SOURCE_TYPE);
        e.setSourceId(SOURCE_ID);
        e.setSnoozedUntil(snoozedUntil);
        e.setArchivedAt(archivedAt);
        return e;
    }

    // ─────────────────────────────────────────────────────────────────
    // snooze
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("snooze")
    class Snooze {

        @Test
        @DisplayName("異常系: 過去時刻 → INBOX_INVALID_SNOOZE_TIME を投げ、保存しない")
        void pastTime_throwsInvalidSnoozeTime() {
            OffsetDateTime past = OffsetDateTime.now(JST).minusHours(1);

            assertThatThrownBy(() -> triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, past))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_INVALID_SNOOZE_TIME);

            verify(itemStateRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 未来時刻・既存行なし → 新規 save（upsert insert）")
        void futureTime_noExisting_inserts() {
            OffsetDateTime future = OffsetDateTime.now(JST).plusHours(3);
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.empty());
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, future);

            verify(itemStateRepository).save(any(InboxItemStateEntity.class));
        }

        @Test
        @DisplayName("正常系: 未来時刻・既存行あり → 既存行を更新（upsert update）")
        void futureTime_existing_updates() {
            OffsetDateTime future = OffsetDateTime.now(JST).plusHours(3);
            InboxItemStateEntity row = existing(null, null);
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, future);

            verify(itemStateRepository).save(row);
        }

        @Test
        @DisplayName("TZ根治: UTC で JST 23:00 を表す入力 → JST 壁時計 23:00 で保存（オフセットを尊重）")
        void utcInput_storedAsJstWallClock() {
            // UTC 未来時刻で JST に変換したときオフセットが正しく反映されることを検証。
            // 旧実装（LocalDateTime 受け）なら Z が捨てられ UTC の時刻そのままが保存されて赤くなる。
            // 固定日時は日付経過で過去になりフレーキーになるため、動的に「来週の月曜 14:00 UTC」を使用。
            java.time.LocalDate nextMonday = java.time.LocalDate.now().plusWeeks(1)
                    .with(java.time.DayOfWeek.MONDAY);
            OffsetDateTime utcInput = OffsetDateTime.of(nextMonday.getYear(), nextMonday.getMonthValue(),
                    nextMonday.getDayOfMonth(), 14, 0, 0, 0, ZoneOffset.UTC);
            LocalDateTime expectedJst = utcInput.atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo"))
                    .toLocalDateTime();
            InboxItemStateEntity row = existing(null, null);
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, utcInput);

            assertThat(row.getSnoozedUntil()).isEqualTo(expectedJst);
        }

        @Test
        @DisplayName("F04.11 Phase3 ②: 再スヌーズ時に snooze_notified_at を NULL に戻す（再度復帰通知可能）")
        void reSnooze_resetsSnoozeNotifiedAt() {
            OffsetDateTime future = OffsetDateTime.now(JST).plusHours(3);
            LocalDateTime expectedJst = future.atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")).toLocalDateTime();
            // 既に一度復帰 push 済み（snooze_notified_at が刻まれている）行を再スヌーズ
            InboxItemStateEntity row = existing(LocalDateTime.now().minusMinutes(1), null);
            row.setSnoozeNotifiedAt(LocalDateTime.now().minusMinutes(1));
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.snooze(USER_ID, SOURCE_TYPE, SOURCE_ID, future);

            assertThat(row.getSnoozeNotifiedAt()).isNull();
            assertThat(row.getSnoozedUntil()).isEqualTo(expectedJst);
            verify(itemStateRepository).save(row);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // archive
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("archive")
    class Archive {

        @Test
        @DisplayName("正常系: 既存行なし → archived_at をセットして save（upsert）")
        void noExisting_upsertsArchivedAt() {
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.empty());
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, SOURCE_ID)).willReturn(true);
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.archive(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository).save(any(InboxItemStateEntity.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // unsnooze（両カラム NULL → 物理削除）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unsnooze")
    class Unsnooze {

        @Test
        @DisplayName("正常系: スヌーズのみの行を解除 → 両カラム NULL になり物理削除（save しない）")
        void snoozeOnly_unsnooze_deletesRow() {
            InboxItemStateEntity row = existing(LocalDateTime.now().plusHours(3), null);
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));

            triageService.unsnooze(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository).delete(row);
            verify(itemStateRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: アーカイブも併存する行を unsnooze → archived_at が残るため削除しない（update）")
        void alsoArchived_unsnooze_keepsRow() {
            InboxItemStateEntity row = existing(LocalDateTime.now().plusHours(3), LocalDateTime.now());
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));
            given(itemStateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            triageService.unsnooze(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository, never()).delete(any());
            verify(itemStateRepository).save(row);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // unarchive（両カラム NULL → 物理削除）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unarchive")
    class Unarchive {

        @Test
        @DisplayName("正常系: アーカイブのみの行を解除 → 両カラム NULL になり物理削除（save しない）")
        void archiveOnly_unarchive_deletesRow() {
            InboxItemStateEntity row = existing(null, LocalDateTime.now());
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, SOURCE_ID))
                    .willReturn(Optional.of(row));

            triageService.unarchive(USER_ID, SOURCE_TYPE, SOURCE_ID);

            verify(itemStateRepository).delete(row);
            verify(itemStateRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // IDOR: 本人に可視でない対象 → INBOX_SOURCE_NOT_FOUND
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IDOR（可視性検証）")
    class Idor {

        @Test
        @DisplayName("異常系: 本人に可視でない通知への snooze → INBOX_SOURCE_NOT_FOUND・行を作らない")
        void notVisible_snooze_throwsSourceNotFound() {
            // 既存オーバーレイ行なし（初回 triage）＋ 可視性チェッカーが false → INBOX_SOURCE_NOT_FOUND
            given(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(USER_ID, SOURCE_TYPE, 999L))
                    .willReturn(Optional.empty());
            given(visibilityChecker.isVisibleTo(USER_ID, SOURCE_TYPE, 999L)).willReturn(false);

            assertThatThrownBy(() -> triageService.snooze(
                    USER_ID, SOURCE_TYPE, 999L, OffsetDateTime.now(JST).plusHours(1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(InboxErrorCode.INBOX_SOURCE_NOT_FOUND);

            verify(itemStateRepository, never()).save(any());
        }
    }
}
