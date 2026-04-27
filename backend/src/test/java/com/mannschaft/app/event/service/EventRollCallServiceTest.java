package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.CheckinType;
import com.mannschaft.app.event.dto.RollCallCandidateResponse;
import com.mannschaft.app.event.dto.RollCallEntryRequest;
import com.mannschaft.app.event.dto.RollCallSessionRequest;
import com.mannschaft.app.event.dto.RollCallSessionResponse;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EventRollCallService} のユニットテスト。F03.12 §14 主催者点呼機能。
 */
@ExtendWith(MockitoExtension.class)
class EventRollCallServiceTest {

    @Mock
    private EventRsvpResponseRepository rsvpResponseRepository;

    @Mock
    private EventCheckinRepository checkinRepository;

    @Mock
    private UserCareLinkRepository careLinkRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CareLinkService careLinkService;

    @Mock
    private CareEventNotificationService careEventNotificationService;

    @InjectMocks
    private EventRollCallService rollCallService;

    private static final Long EVENT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long OPERATOR_USER_ID = 999L;
    private static final Long USER_ID_TARO = 101L;
    private static final Long USER_ID_HANAKO = 102L;
    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    // =========================================================
    // getRollCallCandidates
    // =========================================================

    @Nested
    @DisplayName("getRollCallCandidates")
    class GetRollCallCandidates {

        @Test
        @DisplayName("正常_候補者一覧が返る: ATTENDING/MAYBEのRSVP → candidatesにケアフラグ・watcherCount付与")
        void 正常_候補者一覧が返る() {
            // Arrange
            EventRsvpResponseEntity rsvpTaro = buildRsvp(USER_ID_TARO, "ATTENDING");
            EventRsvpResponseEntity rsvpHanako = buildRsvp(USER_ID_HANAKO, "MAYBE");

            given(rsvpResponseRepository.findAttendingOrMaybeByEventId(EVENT_ID))
                    .willReturn(List.of(rsvpTaro, rsvpHanako));

            // 太郎はケア対象（ケアリンクあり）、花子はケア対象なし
            UserCareLinkEntity careLink = buildCareLink(USER_ID_TARO);
            given(careLinkRepository.findByCareRecipientUserIdInAndStatus(
                    any(), eq(CareLinkStatus.ACTIVE)))
                    .willReturn(List.of(careLink));

            // ユーザー情報（findById 個別呼び出しでモック）
            given(userRepository.findById(USER_ID_TARO))
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田太郎", null)));
            given(userRepository.findById(USER_ID_HANAKO))
                    .willReturn(Optional.of(buildUser(USER_ID_HANAKO, "鈴木花子", null)));

            // チェックイン状態：太郎は未チェックイン、花子は既チェックイン
            given(checkinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID_TARO)).willReturn(false);
            given(checkinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID_HANAKO)).willReturn(true);

            // Act
            List<RollCallCandidateResponse> result =
                    rollCallService.getRollCallCandidates(EVENT_ID, TEAM_ID, OPERATOR_USER_ID);

            // Assert
            assertThat(result).hasSize(2);

            RollCallCandidateResponse taroRes = result.stream()
                    .filter(r -> USER_ID_TARO.equals(r.getUserId()))
                    .findFirst().orElseThrow();
            assertThat(taroRes.getDisplayName()).isEqualTo("山田太郎");
            assertThat(taroRes.getRsvpStatus()).isEqualTo("ATTENDING");
            assertThat(taroRes.isAlreadyCheckedIn()).isFalse();
            assertThat(taroRes.isUnderCare()).isTrue();
            assertThat(taroRes.getWatcherCount()).isEqualTo(1);

            RollCallCandidateResponse hanakoRes = result.stream()
                    .filter(r -> USER_ID_HANAKO.equals(r.getUserId()))
                    .findFirst().orElseThrow();
            assertThat(hanakoRes.getRsvpStatus()).isEqualTo("MAYBE");
            assertThat(hanakoRes.isAlreadyCheckedIn()).isTrue();
            assertThat(hanakoRes.isUnderCare()).isFalse();
            assertThat(hanakoRes.getWatcherCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("RSVPなし_空リストが返る")
        void RSVPなし_空リストが返る() {
            // Arrange
            given(rsvpResponseRepository.findAttendingOrMaybeByEventId(EVENT_ID))
                    .willReturn(List.of());

            // Act
            List<RollCallCandidateResponse> result =
                    rollCallService.getRollCallCandidates(EVENT_ID, TEAM_ID, OPERATOR_USER_ID);

            // Assert
            assertThat(result).isEmpty();
            // IN句クエリが呼ばれていないことを確認
            verify(careLinkRepository, never()).findByCareRecipientUserIdInAndStatus(any(), any());
        }
    }

    // =========================================================
    // submitRollCall
    // =========================================================

    @Nested
    @DisplayName("submitRollCall")
    class SubmitRollCall {

        @Test
        @DisplayName("PRESENT_保護者通知あり: ケア対象者がPRESENT → notifyCheckin が呼ばれること")
        void PRESENT_保護者通知あり() {
            // Arrange
            RollCallEntryRequest entry = new RollCallEntryRequest(USER_ID_TARO, "PRESENT", null, null);
            RollCallSessionRequest request = new RollCallSessionRequest(SESSION_ID, List.of(entry), true);

            // 既存レコードなし（新規作成）
            given(checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(
                    EVENT_ID, SESSION_ID, USER_ID_TARO))
                    .willReturn(Optional.empty());

            // ケア対象者
            given(careLinkService.isUnderCare(USER_ID_TARO)).willReturn(true);
            // 見守り者1人以上（警告なし）
            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(
                    eq(USER_ID_TARO), any()))
                    .willReturn(1L);

            given(userRepository.findById(USER_ID_TARO))
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田太郎", null)));

            EventCheckinEntity savedCheckin = buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, "PRESENT");
            given(checkinRepository.save(any())).willReturn(savedCheckin);

            // Act
            RollCallSessionResponse response =
                    rollCallService.submitRollCall(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, request);

            // Assert
            assertThat(response.getRollCallSessionId()).isEqualTo(SESSION_ID);
            assertThat(response.getCreatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedCount()).isEqualTo(0);
            assertThat(response.getGuardianNotificationsSent()).isEqualTo(1);
            assertThat(response.getGuardianSetupWarnings()).isEmpty();

            // notifyCheckin が呼ばれていること
            verify(careEventNotificationService).notifyCheckin(USER_ID_TARO, EVENT_ID);
        }

        @Test
        @DisplayName("ABSENT_通知なし: ABSENTの場合 notifyCheckin が呼ばれないこと")
        void ABSENT_通知なし() {
            // Arrange
            RollCallEntryRequest entry = new RollCallEntryRequest(USER_ID_TARO, "ABSENT", null, "SICK");
            RollCallSessionRequest request = new RollCallSessionRequest(SESSION_ID, List.of(entry), true);

            given(checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(
                    EVENT_ID, SESSION_ID, USER_ID_TARO))
                    .willReturn(Optional.empty());

            // ケア対象者でも ABSENT なら通知しない（見守り者確認のみ）
            given(careLinkService.isUnderCare(USER_ID_TARO)).willReturn(true);
            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(
                    eq(USER_ID_TARO), any()))
                    .willReturn(1L);

            given(userRepository.findById(USER_ID_TARO))
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田太郎", null)));

            EventCheckinEntity savedCheckin = buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, "ABSENT");
            given(checkinRepository.save(any())).willReturn(savedCheckin);

            // Act
            RollCallSessionResponse response =
                    rollCallService.submitRollCall(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, request);

            // Assert
            assertThat(response.getGuardianNotificationsSent()).isEqualTo(0);

            // notifyCheckin は呼ばれていないこと
            verify(careEventNotificationService, never()).notifyCheckin(anyLong(), anyLong());
        }

        @Test
        @DisplayName("冪等性_同一セッションID+userId を2回送信 → UPDATE（重複なし）")
        void 冪等性_同一セッションID_UPDATE() {
            // Arrange: 1回目
            RollCallEntryRequest entry = new RollCallEntryRequest(USER_ID_TARO, "PRESENT", null, null);
            RollCallSessionRequest request = new RollCallSessionRequest(SESSION_ID, List.of(entry), false);

            EventCheckinEntity existingCheckin = buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, "PRESENT");
            // 既存レコードあり → UPDATE パス
            given(checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(
                    EVENT_ID, SESSION_ID, USER_ID_TARO))
                    .willReturn(Optional.of(existingCheckin));

            given(userRepository.findById(USER_ID_TARO))
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田太郎", null)));
            given(checkinRepository.save(any())).willReturn(existingCheckin);

            // Act
            RollCallSessionResponse response =
                    rollCallService.submitRollCall(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, request);

            // Assert: createdCount=0, updatedCount=1
            assertThat(response.getCreatedCount()).isEqualTo(0);
            assertThat(response.getUpdatedCount()).isEqualTo(1);
            // notifyGuardiansImmediately=false なので通知なし
            assertThat(response.getGuardianNotificationsSent()).isEqualTo(0);
            // save が1回（UPDATE）呼ばれていること
            verify(checkinRepository, times(1)).save(existingCheckin);
        }

        @Test
        @DisplayName("ケア対象で見守り者ゼロ_警告が収集される")
        void ケア対象で見守り者ゼロ_警告収集() {
            // Arrange
            RollCallEntryRequest entry = new RollCallEntryRequest(USER_ID_TARO, "PRESENT", null, null);
            RollCallSessionRequest request = new RollCallSessionRequest(SESSION_ID, List.of(entry), true);

            given(checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(
                    EVENT_ID, SESSION_ID, USER_ID_TARO))
                    .willReturn(Optional.empty());

            // ケア対象者だが見守り者ゼロ
            given(careLinkService.isUnderCare(USER_ID_TARO)).willReturn(true);
            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(
                    eq(USER_ID_TARO), any()))
                    .willReturn(0L);

            given(userRepository.findById(USER_ID_TARO))
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田太郎", null)));
            given(checkinRepository.save(any()))
                    .willReturn(buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, "PRESENT"));

            // Act
            RollCallSessionResponse response =
                    rollCallService.submitRollCall(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, request);

            // Assert: 警告が1件収集されること
            assertThat(response.getGuardianNotificationsSent()).isEqualTo(0);
            assertThat(response.getGuardianSetupWarnings()).hasSize(1);
            assertThat(response.getGuardianSetupWarnings().get(0)).contains("山田太郎");

            // 通知は送信されないこと
            verify(careEventNotificationService, never()).notifyCheckin(anyLong(), anyLong());
        }

        @Test
        @DisplayName("複数エントリ_PRESENTとABSENTが混在: PRESENTのケア対象のみ通知")
        void 複数エントリ_混在() {
            // Arrange
            RollCallEntryRequest entryTaro = new RollCallEntryRequest(USER_ID_TARO, "PRESENT", null, null);
            RollCallEntryRequest entryHanako = new RollCallEntryRequest(USER_ID_HANAKO, "ABSENT", null, "SICK");
            RollCallSessionRequest request = new RollCallSessionRequest(
                    SESSION_ID, List.of(entryTaro, entryHanako), true);

            given(checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(
                    eq(EVENT_ID), eq(SESSION_ID), anyLong()))
                    .willReturn(Optional.empty());

            given(userRepository.findById(USER_ID_TARO))
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田太郎", null)));
            given(userRepository.findById(USER_ID_HANAKO))
                    .willReturn(Optional.of(buildUser(USER_ID_HANAKO, "鈴木花子", null)));

            // 太郎: ケア対象あり、見守り者1人
            given(careLinkService.isUnderCare(USER_ID_TARO)).willReturn(true);
            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(eq(USER_ID_TARO), any()))
                    .willReturn(1L);
            // 花子: ケア対象あり（ABSENTなので通知なし、警告チェックのみ）
            given(careLinkService.isUnderCare(USER_ID_HANAKO)).willReturn(true);
            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(eq(USER_ID_HANAKO), any()))
                    .willReturn(1L);

            given(checkinRepository.save(any()))
                    .willAnswer(inv -> inv.getArgument(0));

            // Act
            RollCallSessionResponse response =
                    rollCallService.submitRollCall(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, request);

            // Assert
            assertThat(response.getCreatedCount()).isEqualTo(2);
            assertThat(response.getGuardianNotificationsSent()).isEqualTo(1);

            // 太郎のみ notifyCheckin が呼ばれること
            verify(careEventNotificationService).notifyCheckin(USER_ID_TARO, EVENT_ID);
            verify(careEventNotificationService, never()).notifyCheckin(USER_ID_HANAKO, EVENT_ID);
        }
    }

    // =========================================================
    // テストヘルパー
    // =========================================================

    private EventRsvpResponseEntity buildRsvp(Long userId, String response) {
        return EventRsvpResponseEntity.builder()
                .eventId(EVENT_ID)
                .userId(userId)
                .response(response)
                .build();
    }

    private UserCareLinkEntity buildCareLink(Long careRecipientUserId) {
        return UserCareLinkEntity.builder()
                .careRecipientUserId(careRecipientUserId)
                .watcherUserId(888L)
                .careCategory(CareCategory.MINOR)
                .invitedBy(CareLinkInvitedBy.CARE_RECIPIENT)
                .createdBy(careRecipientUserId)
                .status(CareLinkStatus.ACTIVE)
                .build();
    }

    private UserEntity buildUser(Long id, String displayName, String avatarUrl) {
        return UserEntity.builder()
                .displayName(displayName)
                .email("test" + id + "@example.com")
                .lastName("テスト")
                .firstName("ユーザー" + id)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
    }

    private EventCheckinEntity buildCheckin(Long eventId, String sessionId, Long userId, String status) {
        return EventCheckinEntity.builder()
                .eventId(eventId)
                .rollCallUserId(userId)
                .checkinType(CheckinType.ROLL_CALL_BATCH)
                .rollCallSessionId(sessionId)
                .checkedInBy(OPERATOR_USER_ID)
                .build();
    }
}
