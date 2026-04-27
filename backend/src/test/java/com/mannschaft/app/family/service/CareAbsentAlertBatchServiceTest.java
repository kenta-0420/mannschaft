package com.mannschaft.app.family.service;

import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.dto.CareLinkResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link CareAbsentAlertBatchService} のユニットテスト。F03.12 Phase4。
 */
@ExtendWith(MockitoExtension.class)
class CareAbsentAlertBatchServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventRsvpResponseRepository rsvpResponseRepository;

    @Mock
    private EventCheckinRepository eventCheckinRepository;

    @Mock
    private CareLinkService careLinkService;

    @Mock
    private CareEventNotificationService careEventNotificationService;

    @InjectMocks
    private CareAbsentAlertBatchService batchService;

    private static final Long EVENT_ID = 1L;
    private static final Long USER_ID = 100L;

    // =========================================================
    // runNoContactCheck
    // =========================================================

    @Nested
    @DisplayName("runNoContactCheck")
    class RunNoContactCheck {

        @Test
        @DisplayName("正常_対象者に通知送信: 進行中イベント・ATTENDING・未チェックイン・遅刻連絡なし・isUnderCare=true → sendNoContactCheck が呼ばれること")
        void 正常_対象者に通知送信() {
            // Arrange
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            CareLinkResponse link = buildCareLinkResponse(USER_ID, CareCategory.MINOR);

            // findActiveEventIdsStartedBefore は2回呼ばれる
            // 1回目: 粗フィルタ(5min), 2回目: カテゴリ固有フィルタ(10min for MINOR)
            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            // F03.12 Phase8: 本体は遅刻連絡の有無に関わらず一括取得し、メモリでオフセット計算する仕様に変更された。
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(careLinkService.getActiveLinksForCareRecipient(USER_ID)).willReturn(List.of(link));

            // Act
            batchService.runNoContactCheck();

            // Assert
            verify(careEventNotificationService).sendNoContactCheck(USER_ID, EVENT_ID);
        }

        @Test
        @DisplayName("チェックイン済みはスキップ: チェックイン済み → sendNoContactCheck が呼ばれないこと")
        void チェックイン済みはスキップ() {
            // Arrange
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);

            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            // F03.12 Phase8: 本体は遅刻連絡の有無に関わらず一括取得する仕様に変更された。
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            // チェックイン済み: 早期 return するためカテゴリ解決には到達せず getActiveLinksForCareRecipient は呼ばれない。
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(true);

            // Act
            batchService.runNoContactCheck();

            // Assert: sendNoContactCheck は呼ばれないこと
            verify(careEventNotificationService, never()).sendNoContactCheck(anyLong(), anyLong());
        }

        @Test
        @DisplayName("事前欠席連絡済みはスキップ: advanceAbsenceReason 設定済み → sendNoContactCheck が呼ばれないこと")
        void 事前欠席連絡済みはスキップ() {
            // Arrange: Phase8 §15 — 事前欠席連絡済みのケア対象者は NO_CONTACT_CHECK 対象外
            EventRsvpResponseEntity rsvp = buildRsvpWithAdvanceAbsence(EVENT_ID, USER_ID, "SICK");

            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            // 事前欠席連絡済みは早期 return するためチェックイン照会後にスキップされる。
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);

            // Act
            batchService.runNoContactCheck();

            // Assert: sendNoContactCheck は呼ばれないこと
            verify(careEventNotificationService, never()).sendNoContactCheck(anyLong(), anyLong());
        }
    }

    // =========================================================
    // runAbsentAlertCheck
    // =========================================================

    @Nested
    @DisplayName("runAbsentAlertCheck")
    class RunAbsentAlertCheck {

        @Test
        @DisplayName("正常_対象者に通知送信: absent_alert_minutes 経過 → sendAbsentAlert が呼ばれること")
        void 正常_対象者に通知送信() {
            // Arrange
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            CareLinkResponse link = buildCareLinkResponse(USER_ID, CareCategory.ELDERLY);

            // findActiveEventIdsStartedBefore は2回呼ばれる
            // 1回目: 粗フィルタ(15min), 2回目: カテゴリ固有フィルタ(15min for ELDERLY)
            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            // F03.12 Phase8: 本体は遅刻連絡の有無に関わらず一括取得し、メモリでオフセット計算する仕様に変更された。
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(careLinkService.getActiveLinksForCareRecipient(USER_ID)).willReturn(List.of(link));

            // Act
            batchService.runAbsentAlertCheck();

            // Assert
            verify(careEventNotificationService).sendAbsentAlert(USER_ID, EVENT_ID);
        }

        @Test
        @DisplayName("事前欠席連絡済みはスキップ: advanceAbsenceReason 設定済み → sendAbsentAlert が呼ばれないこと")
        void 事前欠席連絡済みはスキップ() {
            // Arrange: Phase8 §15 — 事前欠席連絡済みのケア対象者は ABSENT_ALERT 対象外
            EventRsvpResponseEntity rsvp = buildRsvpWithAdvanceAbsence(EVENT_ID, USER_ID, "SICK");

            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);

            // Act
            batchService.runAbsentAlertCheck();

            // Assert: sendAbsentAlert は呼ばれないこと
            verify(careEventNotificationService, never()).sendAbsentAlert(anyLong(), anyLong());
        }
    }

    // =========================================================
    // テストヘルパー
    // =========================================================

    private EventRsvpResponseEntity buildRsvp(Long eventId, Long userId) {
        return EventRsvpResponseEntity.builder()
                .eventId(eventId)
                .userId(userId)
                .response("ATTENDING")
                .build();
    }

    private EventRsvpResponseEntity buildRsvpWithAdvanceAbsence(Long eventId, Long userId, String reason) {
        return EventRsvpResponseEntity.builder()
                .eventId(eventId)
                .userId(userId)
                .response("ATTENDING")
                .advanceAbsenceReason(reason)
                .build();
    }

    private CareLinkResponse buildCareLinkResponse(Long userId, CareCategory category) {
        return CareLinkResponse.builder()
                .id(1L)
                .careRecipientUserId(userId)
                .watcherUserId(999L)
                .careCategory(category)
                .build();
    }
}
