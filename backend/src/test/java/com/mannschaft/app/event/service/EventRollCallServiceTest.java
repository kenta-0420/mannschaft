package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.event.CheckinType;
import com.mannschaft.app.event.dto.RollCallCandidateResponse;
import com.mannschaft.app.event.dto.RollCallEntryRequest;
import com.mannschaft.app.event.dto.RollCallSessionRequest;
import com.mannschaft.app.event.dto.RollCallSessionResponse;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.event.EventCareNotificationTriggerEvent;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.family.service.CareLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

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

            // ユーザー情報（findByIdIn 一括取得でモック・N+1 解消後）
            given(userRepository.findByIdIn(any()))
                    .willReturn(List.of(
                            buildUser(USER_ID_TARO, "山田", "太郎", null),
                            buildUser(USER_ID_HANAKO, "鈴木", "花子", null)));

            // チェックイン状態：太郎は未チェックイン、花子は既チェックイン（IN 句一括取得）
            given(checkinRepository.findCheckedInUserIdsByEventIdAndUserIdIn(eq(EVENT_ID), any()))
                    .willReturn(List.of(USER_ID_HANAKO));

            // Act
            List<RollCallCandidateResponse> result =
                    rollCallService.getRollCallCandidates(EVENT_ID, TEAM_ID, OPERATOR_USER_ID);

            // Assert
            assertThat(result).hasSize(2);

            RollCallCandidateResponse taroRes = result.stream()
                    .filter(r -> USER_ID_TARO.equals(r.getUserId()))
                    .findFirst().orElseThrow();
            assertThat(taroRes.getFullName()).isEqualTo("山田 太郎");
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
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田", "太郎", null)));

            EventCheckinEntity savedCheckin = buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, null, null);
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

            // 見守り通知の配送要求が publish されていること（実配送は AFTER_COMMIT・Issue #2990 L5）
            verifyCareNotificationPublished(List.of(USER_ID_TARO));
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
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田", "太郎", null)));

            EventCheckinEntity savedCheckin = buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, null, "SICK");
            given(checkinRepository.save(any())).willReturn(savedCheckin);

            // Act
            RollCallSessionResponse response =
                    rollCallService.submitRollCall(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, request);

            // Assert
            assertThat(response.getGuardianNotificationsSent()).isEqualTo(0);

            // 見守り通知の配送要求は publish されないこと
            verifyNoCareNotificationPublished();
        }

        @Test
        @DisplayName("冪等性_同一セッションID+userId を2回送信 → UPDATE（重複なし）")
        void 冪等性_同一セッションID_UPDATE() {
            // Arrange: 1回目
            RollCallEntryRequest entry = new RollCallEntryRequest(USER_ID_TARO, "PRESENT", null, null);
            RollCallSessionRequest request = new RollCallSessionRequest(SESSION_ID, List.of(entry), false);

            EventCheckinEntity existingCheckin = buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, null, null);
            // 既存レコードあり → UPDATE パス
            given(checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(
                    EVENT_ID, SESSION_ID, USER_ID_TARO))
                    .willReturn(Optional.of(existingCheckin));

            given(userRepository.findById(USER_ID_TARO))
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田", "太郎", null)));
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
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田", "太郎", null)));
            given(checkinRepository.save(any()))
                    .willReturn(buildCheckin(EVENT_ID, SESSION_ID, USER_ID_TARO, null, null));

            // Act
            RollCallSessionResponse response =
                    rollCallService.submitRollCall(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, request);

            // Assert: 警告が1件収集されること
            assertThat(response.getGuardianNotificationsSent()).isEqualTo(0);
            assertThat(response.getGuardianSetupWarnings()).hasSize(1);
            assertThat(response.getGuardianSetupWarnings().get(0)).contains("山田 太郎");

            // 通知の配送要求は publish されないこと
            verifyNoCareNotificationPublished();
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
                    .willReturn(Optional.of(buildUser(USER_ID_TARO, "山田", "太郎", null)));
            given(userRepository.findById(USER_ID_HANAKO))
                    .willReturn(Optional.of(buildUser(USER_ID_HANAKO, "鈴木", "花子", null)));

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

            // 太郎のみが配送要求の宛先に載ること（花子は ABSENT のため載らない）
            verifyCareNotificationPublished(List.of(USER_ID_TARO));
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

    private UserEntity buildUser(Long id, String lastName, String firstName, String avatarUrl) {
        UserEntity user = UserEntity.builder()
                .displayName(lastName + firstName)
                .avatarUrl(avatarUrl)
                .email("test" + id + "@example.com")
                .lastName(lastName)
                .firstName(firstName)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
        // id は BaseEntity に定義されており @Builder のフィールドに含まれないため、
        // テストでは ReflectionTestUtils で直接注入する。
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private EventCheckinEntity buildCheckin(Long eventId, String sessionId, Long userId,
                                            Integer lateArrivalMinutes, String absenceReason) {
        return EventCheckinEntity.builder()
                .eventId(eventId)
                .rollCallUserId(userId)
                .checkinType(CheckinType.ROLL_CALL_BATCH)
                .rollCallSessionId(sessionId)
                .checkedInBy(OPERATOR_USER_ID)
                .lateArrivalMinutes(lateArrivalMinutes)
                .absenceReason(absenceReason)
                .build();
    }

    /**
     * ケア対象者見守り通知の配送要求が、指定した宛先だけを載せて publish されたことを検証する
     * （Issue #2990 L5）。
     *
     * <p>是正前は {@code CareEventNotificationService} を直接呼んでおり、その Service をモックして
     * いたため、通知が業務トランザクションに参加している事実（= 通知失敗で点呼セッション 1 回ぶんの
     * 出欠が全件巻き戻る）を本 UT は一切捕まえられなかった。是正後は publish の検証に置き換え、
     * 実際の巻き戻り有無は {@code EventCareNotificationTransactionIT}（実 DB）で測る。</p>
     */
    private void verifyCareNotificationPublished(List<Long> expectedRecipients) {
        verify(eventPublisher).publishEvent(ArgumentMatchers.<Object>argThat(
                published -> published instanceof EventCareNotificationTriggerEvent trigger
                        && trigger.kind() == EventCareNotificationTriggerEvent.Kind.CHECKIN
                        && trigger.eventId().equals(EVENT_ID)
                        && trigger.careRecipientUserIds().equals(expectedRecipients)));
    }

    /** 見守り通知の配送要求が一切 publish されていないことを検証する。 */
    private void verifyNoCareNotificationPublished() {
        verify(eventPublisher, never()).publishEvent(
                ArgumentMatchers.<Object>argThat(EventCareNotificationTriggerEvent.class::isInstance));
    }
}
