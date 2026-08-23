package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.CreateReservationGroupRequest;
import com.mannschaft.app.reservation.dto.ReservationGroupCancelResponse;
import com.mannschaft.app.reservation.dto.ReservationGroupResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuLineEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationCancelledByMemberEvent;
import com.mannschaft.app.reservation.event.ReservationConfirmedEvent;
import com.mannschaft.app.reservation.event.ReservationCreatedEvent;
import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationGroupService} の単体テスト（F03.4.3 §8 AC G-1〜G-9 / G-12 / G-14 のドメイン層）。
 *
 * <p>実 DB のロック挙動（G-2 の全ロールバック実体・G-13 並行）は
 * {@code ReservationGroupPersistenceIntegrationTest} / {@code ReservationGroupConcurrencyIntegrationTest}
 * が担う。本 UT は検証順序・エラーコード契約・イベント発行回数（G-9 ①層）・確保順序（slotId 昇順）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationGroupService 単体テスト（機能G）")
class ReservationGroupServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationSlotService slotService;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private ReservationMenuRepository menuRepository;
    @Mock
    private ReservationMenuLineRepository menuLineRepository;
    @Mock
    private ReservationBlockedTimeRepository blockedTimeRepository;
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository
            recurringBlockedTimeRepository;
    @Mock
    private ReservationReminderRepository reminderRepository;
    @Mock
    private ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private ReservationPolicyService reservationPolicyService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuditLogService auditLogService;

    @Mock
    private TeamTimezoneResolver teamTimezoneResolver;

    /** 機能B: overlap 判定は純ロジックのため実インスタンスを注入（RESERVATION_009 の実 throw を検証）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker = new ReservationUnavailabilityChecker();

    private ReservationGroupService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long ADMIN_USER_ID = 300L;
    private static final Long LINE_ID = 30L;
    private static final UUID MENU_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final LocalDate SLOT_DATE = LocalDate.of(2026, 4, 1);

    /** 枠開始（2026-04-01 10:00）の 1 ヶ月前 = 締切内・未来枠の基準時刻。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 0, 0);

    @BeforeEach
    void setUp() {
        reinitServiceWithClockAt(NOW);

        // 既定スタブ: view ゲート通過・重複なし・予約不可枠なし・確保成功・保存はそのまま返す。
        given(slotRepository.findAllById(anyIterable())).willReturn(List.of(slot(101L, 10, 0), slot(102L, 10, 30)));
        given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(java.util.Optional.of(line(true)));
        given(lineRepository.findById(LINE_ID)).willReturn(java.util.Optional.of(line(true)));
        given(menuRepository.findByIdAndTeamId(MENU_ID, TEAM_ID)).willReturn(java.util.Optional.of(menu(60, true)));
        given(menuRepository.findByIdIncludingDeleted(MENU_ID)).willReturn(java.util.Optional.of(menu(60, true)));
        given(menuLineRepository.findByMenuId(MENU_ID)).willReturn(List.of());
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(eq(TEAM_ID), any()))
                .willReturn(List.of());
        given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(anyLong(), anyLong(), anyList()))
                .willReturn(false);
        given(slotRepository.incrementBookedCountIfAvailable(anyLong())).willReturn(1);
        willAnswer(inv -> inv.getArgument(0)).given(reservationRepository).saveAll(anyList());
        willAnswer(inv -> inv.getArgument(0)).given(reminderRepository).saveAll(anyList());
        given(reminderRepository.findByReservationIdOrderByRemindAtAsc(anyLong())).willReturn(List.of());
        given(reservationPolicyService.resolveApprovalMode(eq(TEAM_ID), any(ReservationSlotEntity.class)))
                .willReturn(ApprovalMode.AUTO);
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).build());
        given(accessControlService.isAdminOrAbove(anyLong(), eq(TEAM_ID), eq("TEAM"))).willReturn(false);
        given(accessControlService.isAdminOrAbove(eq(ADMIN_USER_ID), eq(TEAM_ID), eq("TEAM"))).willReturn(true);
    }

    /**
     * 固定 Clock を差し替えてサービスを再生成する（締切・過去枠判定の時刻別検証用）。
     *
     * <p>Issue #2526 是正済み判定: 過去枠・締切判定は
     * {@code LocalDateTime.now(clock.withZone(ZoneId.systemDefault()))} で業務ローカル基準に揃えたため、
     * この Clock が表す瞬間は「JVM 既定ゾーンで解釈すると {@code now} になる」ものでなければならない。
     * かつての {@code now.toInstant(ZoneOffset.UTC)} は UTC 基準比較というバグ実装をそのまま固定していた
     * （実行環境の JVM 既定ゾーンが UTC でない場合に破綻する）。{@link ZoneId#systemDefault()} 経由で
     * instant 化することで、実行環境に関わらず引数 {@code now} が正しく「現在時刻」として渡るようにする。
     */
    private void reinitServiceWithClockAt(LocalDateTime now) {
        reinitServiceWithClock(Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.of("UTC")));
    }

    /**
     * 任意の {@link Clock}（ゾーン込み）を明示注入してサービスを再生成する。
     * Issue #2526 のゾーン一致性番人テスト（同一瞬間・異なる Clock ゾーン）で使う。
     */
    private void reinitServiceWithClock(Clock fixed) {
        TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new ReservationGroupService(
                reservationRepository, slotRepository, slotService, lineRepository, menuRepository,
                menuLineRepository, blockedTimeRepository, recurringBlockedTimeRepository, reminderRepository,
                viewAccessGuard,
                reservationPolicyService, unavailabilityChecker, accessControlService, eventPublisher,
                auditLogService,
                org.mockito.Mockito.mock(com.mannschaft.app.reservation.service.ReservationWaitlistService.class),
                // F03.4.5 §6.4: レートリミットは本テストの対象外のため素通しの mock（判定は
                // ReservationCreateRateLimiterTest / ReservationCreateRateLimitPathTest が担う）。
                org.mockito.Mockito.mock(ReservationCreateRateLimiter.class),
                txTemplate, fixed);
    }

    // ========================================
    // テストデータヘルパー
    // ========================================

    /** 30分セル枠（同一日・共通枠 line_id NULL）。 */
    private static ReservationSlotEntity slot(Long id, int hour, int minute) {
        LocalTime start = LocalTime.of(hour, minute);
        return ReservationSlotEntity.builder()
                .id(id)
                .teamId(TEAM_ID)
                .slotDate(SLOT_DATE)
                .startTime(start)
                .endTime(start.plusMinutes(30))
                .title("枠" + id)
                .build();
    }

    /** ライン軸枠（line_id 指定）。 */
    private static ReservationSlotEntity lineSlot(Long id, int hour, int minute, Long lineId) {
        LocalTime start = LocalTime.of(hour, minute);
        return ReservationSlotEntity.builder()
                .id(id)
                .teamId(TEAM_ID)
                .slotDate(SLOT_DATE)
                .startTime(start)
                .endTime(start.plusMinutes(30))
                .lineId(lineId)
                .build();
    }

    private static ReservationLineEntity line(boolean active) {
        return ReservationLineEntity.builder()
                .id(LINE_ID)
                .teamId(TEAM_ID)
                .name("席1")
                .isActive(active)
                .build();
    }

    private static ReservationMenuEntity menu(int durationMinutes, boolean active) {
        ReservationMenuEntity entity = ReservationMenuEntity.builder()
                .teamId(TEAM_ID)
                .name("カット")
                .durationMinutes(durationMinutes)
                .price(new BigDecimal("4500.00"))
                .isActive(active)
                .build();
        entity.setId(MENU_ID);
        return entity;
    }

    private static CreateReservationGroupRequest request(UUID menuId, List<Long> slotIds) {
        return new CreateReservationGroupRequest(menuId, LINE_ID, slotIds, "初めての利用です");
    }

    /** グループ兄弟行（先頭 501 が代表行・slot 101/102）。 */
    private List<ReservationEntity> groupRows(ReservationStatus status) {
        ReservationEntity primary = ReservationEntity.builder()
                .id(501L).reservationSlotId(101L).lineId(LINE_ID).teamId(TEAM_ID).userId(USER_ID)
                .groupId(GROUP_ID).menuId(MENU_ID).isGroupPrimary(true)
                .status(status).userNote("初めての利用です")
                .build();
        ReservationEntity sibling = ReservationEntity.builder()
                .id(502L).reservationSlotId(102L).lineId(LINE_ID).teamId(TEAM_ID).userId(USER_ID)
                .groupId(GROUP_ID).menuId(MENU_ID).isGroupPrimary(false)
                .status(status)
                .build();
        return List.of(primary, sibling);
    }

    private void stubGroupRows(ReservationStatus status) {
        given(reservationRepository.findByGroupIdAndTeamIdOrderById(GROUP_ID, TEAM_ID))
                .willReturn(groupRows(status));
    }

    private BusinessException catchBusiness(Runnable runnable) {
        try {
            runnable.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("BusinessException が throw されること");
    }

    /** publishEvent へ渡った全イベントを型別に数える（G-9 ①層の観測点）。 */
    private List<Object> capturedEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    // ========================================
    // createGroup（G-1〜G-6 / G-9）
    // ========================================

    @Nested
    @DisplayName("createGroup: 正常系（G-1）")
    class CreateGroupHappyPath {

        @Test
        @DisplayName("非JST境界: America/New_Yorkの未来枠でグループ予約を作成できる")
        void 非JST境界の未来枠でグループ予約を作成できる() {
            reinitServiceWithClock(Clock.fixed(Instant.parse("2026-08-10T02:30:00Z"), ZoneOffset.UTC));
            ReflectionTestUtils.setField(service, "teamTimezoneResolver", teamTimezoneResolver);
            given(teamTimezoneResolver.resolveZone(TEAM_ID)).willReturn(ZoneId.of("America/New_York"));
            ReservationSlotEntity first = ReservationSlotEntity.builder().id(101L).teamId(TEAM_ID)
                    .slotDate(LocalDate.of(2026, 8, 9)).startTime(LocalTime.of(23, 0))
                    .endTime(LocalTime.of(23, 30)).build();
            ReservationSlotEntity second = ReservationSlotEntity.builder().id(102L).teamId(TEAM_ID)
                    .slotDate(LocalDate.of(2026, 8, 9)).startTime(LocalTime.of(23, 30))
                    .endTime(LocalTime.of(23, 59)).build();
            given(slotRepository.findAllById(anyIterable())).willReturn(List.of(first, second));
            given(teamTimezoneResolver.toInstant(first.getSlotDate(), first.getStartTime(), ZoneId.of("America/New_York")))
                    .willReturn(Instant.parse("2026-08-10T03:00:00Z"));
            given(teamTimezoneResolver.toInstant(second.getSlotDate(), second.getStartTime(), ZoneId.of("America/New_York")))
                    .willReturn(Instant.parse("2026-08-10T03:30:00Z"));

            ReservationGroupResponse response =
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L)));

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("G-1: AUTO 確定 — 2枠が同一 groupId で INSERT され代表行は先頭のみ・全行 CONFIRMED")
        void 自動確定でグループ作成() {
            ReservationGroupResponse response =
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L)));

            assertThat(response.getGroupId()).isNotNull();
            assertThat(response.getStatus()).isEqualTo("CONFIRMED");
            assertThat(response.getSlotCount()).isEqualTo(2);
            assertThat(response.getSlotDate()).isEqualTo(SLOT_DATE);
            assertThat(response.getStartTime()).isEqualTo(LocalTime.of(10, 0));
            assertThat(response.getEndTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(response.getMenuName()).isEqualTo("カット");
            assertThat(response.getPrice()).isEqualByComparingTo("4500.00");
            assertThat(response.getReservations()).hasSize(2);
            assertThat(response.getReservations().get(0).isGroupPrimary()).isTrue();
            assertThat(response.getReservations().get(1).isGroupPrimary()).isFalse();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ReservationEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(reservationRepository).saveAll(captor.capture());
            List<ReservationEntity> saved = captor.getValue();
            assertThat(saved).hasSize(2);
            assertThat(saved).extracting(ReservationEntity::getGroupId).containsOnly(saved.get(0).getGroupId());
            assertThat(saved.get(0).getGroupId()).isNotNull();
            assertThat(saved).extracting(ReservationEntity::getStatus).containsOnly(ReservationStatus.CONFIRMED);
            assertThat(saved).extracting(ReservationEntity::getMenuId).containsOnly(MENU_ID);
            assertThat(saved).extracting(ReservationEntity::getLineId).containsOnly(LINE_ID);
            // 代表行 = 先頭枠（10:00 の slot 101）のみ TRUE・userNote は代表行のみ（§3.2）
            ReservationEntity primary = saved.stream().filter(ReservationEntity::getIsGroupPrimary).findFirst().orElseThrow();
            assertThat(saved.stream().filter(ReservationEntity::getIsGroupPrimary)).hasSize(1);
            assertThat(primary.getReservationSlotId()).isEqualTo(101L);
            assertThat(primary.getUserNote()).isEqualTo("初めての利用です");
            ReservationEntity sibling = saved.stream().filter(e -> !e.getIsGroupPrimary()).findFirst().orElseThrow();
            assertThat(sibling.getUserNote()).isNull();
        }

        @Test
        @DisplayName("G-13 の実装順序前提: 確保 UPDATE は slotId 昇順で呼ばれる（リクエスト順は逆でも）")
        void 確保はslotId昇順() {
            service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(102L, 101L)));

            InOrder order = inOrder(slotRepository);
            order.verify(slotRepository).incrementBookedCountIfAvailable(101L);
            order.verify(slotRepository).incrementBookedCountIfAvailable(102L);
        }

        @Test
        @DisplayName("メニューなし自由グループ — price は null（枠単価合算は表示しない・§4）")
        void メニューなし自由グループ() {
            ReservationGroupResponse response =
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L)));

            assertThat(response.getMenuId()).isNull();
            assertThat(response.getMenuName()).isNull();
            assertThat(response.getPrice()).isNull();
            assertThat(response.getSlotCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("createGroup: 確保失敗・全ロールバック契約（G-2）")
    class CreateGroupAcquisitionFailure {

        @Test
        @DisplayName("G-2: 2枠目の確保 0 行更新 → 409=RESERVATION_039・INSERT に到達しない")
        void 一部枠の確保失敗は039() {
            given(slotRepository.incrementBookedCountIfAvailable(102L)).willReturn(0);

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_SLOT_UNAVAILABLE);
            verify(reservationRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("§5.2: InnoDB デッドロック（PessimisticLockingFailure）は 409=039 の「選び直し」契約へマップ")
        void デッドロックは039へ変換() {
            given(slotRepository.incrementBookedCountIfAvailable(101L))
                    .willThrow(new CannotAcquireLockException("deadlock"));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_SLOT_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("createGroup: 連続性・枠数・ライン検証（G-3 / G-4）")
    class CreateGroupValidation {

        @Test
        @DisplayName("G-3: 間欠枠（10:00-10:30 と 11:00-11:30）は 400=038")
        void 非連続枠は038() {
            given(slotRepository.findAllById(anyIterable()))
                    .willReturn(List.of(slot(101L, 10, 0), slot(103L, 11, 0)));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 103L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        @Test
        @DisplayName("G-3: 別日混在は 400=038")
        void 別日混在は038() {
            ReservationSlotEntity otherDay = ReservationSlotEntity.builder()
                    .id(103L).teamId(TEAM_ID)
                    .slotDate(SLOT_DATE.plusDays(1))
                    .startTime(LocalTime.of(10, 30)).endTime(LocalTime.of(11, 0))
                    .build();
            given(slotRepository.findAllById(anyIterable()))
                    .willReturn(List.of(slot(101L, 10, 0), otherDay));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 103L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        @Test
        @DisplayName("G-3: 別ライン軸枠の混在（slot.line_id ≠ request.lineId）は 400=038")
        void ライン不一致は038() {
            given(slotRepository.findAllById(anyIterable()))
                    .willReturn(List.of(slot(101L, 10, 0), lineSlot(102L, 10, 30, 99L)));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        @Test
        @DisplayName("slotIds の重複指定は 400=038")
        void slotIds重複は038() {
            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 101L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        @Test
        @DisplayName("G-4: 17 枠は 400=041（GROUP_SIZE_EXCEEDED）")
        void 十七枠は041() {
            List<Long> ids = new ArrayList<>();
            for (long i = 0; i < 17; i++) {
                ids.add(101L + i);
            }

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, ids)));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_SIZE_EXCEEDED);
        }

        @Test
        @DisplayName("G-4: 16 枠（上限ちょうど）は成立する")
        void 十六枠は成立() {
            List<Long> ids = new ArrayList<>();
            List<ReservationSlotEntity> slots = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                ids.add(101L + i);
                slots.add(slot(101L + i, 9 + (i / 2), (i % 2) * 30));
            }
            given(slotRepository.findAllById(anyIterable())).willReturn(slots);

            ReservationGroupResponse response =
                    service.createGroup(TEAM_ID, USER_ID, request(null, ids));

            assertThat(response.getSlotCount()).isEqualTo(16);
        }

        @Test
        @DisplayName("G-4: 必要枠数 2 のメニューに 1 枠は 400=038")
        void 必要枠数不足は038() {
            given(slotRepository.findAllById(anyIterable())).willReturn(List.of(slot(101L, 10, 0)));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        @Test
        @DisplayName("G-4: 必要枠数 2 のメニューに 3 枠（延長）は成立する")
        void 延長3枠は成立() {
            given(slotRepository.findAllById(anyIterable()))
                    .willReturn(List.of(slot(101L, 10, 0), slot(102L, 10, 30), slot(103L, 11, 0)));

            ReservationGroupResponse response =
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L, 103L)));

            assertThat(response.getSlotCount()).isEqualTo(3);
            assertThat(response.getEndTime()).isEqualTo(LocalTime.of(11, 30));
        }

        @Test
        @DisplayName("枠の一部が不存在/他チームなら 404=002")
        void 枠不足は002() {
            given(slotRepository.findAllById(anyIterable())).willReturn(List.of(slot(101L, 10, 0)));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
        }

        @Test
        @DisplayName("ライン不存在は 404=001")
        void ライン不存在は001() {
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(java.util.Optional.empty());

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
        }

        @Test
        @DisplayName("無効ライン（is_active=FALSE）は 400=038")
        void 無効ラインは038() {
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(java.util.Optional.of(line(false)));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        @Test
        @DisplayName("過去枠（先頭枠開始が現在以前）は 400=014")
        void 過去枠は014() {
            reinitServiceWithClockAt(LocalDateTime.of(2026, 5, 1, 0, 0));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.PAST_DATE_RESERVATION);
        }

        @Test
        @DisplayName(
                "Issue #2526 番人: 過去枠判定は Clock のゾーンに左右されず、同一瞬間なら結果が一致する")
        void 過去枠判定はClockのゾーンに左右されない() {
            // 先頭枠開始は SLOT_DATE(2026-04-01) 10:00（業務ローカル時刻）。
            // 「業務基準（JVM 既定ゾーン。実行環境に依存し得るため決め打ちしない）で見て枠開始の 1 分前」
            // ＝2026-04-01T09:59 を、実際の JVM 既定ゾーンで instant 化した「同一瞬間」を、
            // ゾーン設定だけが異なる 2 つの Clock（UTC / Asia+09:00）で表現する。
            Instant sameInstant = LocalDateTime.of(2026, 4, 1, 9, 59)
                    .atZone(ZoneId.systemDefault()).toInstant();

            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneOffset.UTC));
            ReservationGroupResponse resultUtc = service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L)));
            assertThat(resultUtc).as("UTC Clock: 未来枠のはずなので成功する").isNotNull();

            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneId.of("Asia/Tokyo")));
            ReservationGroupResponse resultTokyo = service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L)));
            assertThat(resultTokyo)
                    .as("Clock のゾーン設定が判定結果に漏れ出してはならない（同一瞬間なら UTC と同じ『未来枠』判定になるはず）")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("createGroup: メニュー検証（G-5 / 032 / 043）")
    class CreateGroupMenuValidation {

        @Test
        @DisplayName("G-5: menu_lines が他ラインのみ列挙 → 400=043（GROUP_MENU_LINE_NOT_OFFERED）")
        void 提供外ラインは043() {
            given(menuLineRepository.findByMenuId(MENU_ID)).willReturn(List.of(
                    ReservationMenuLineEntity.builder().menuId(MENU_ID).lineId(40L).build()));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_MENU_LINE_NOT_OFFERED);
        }

        @Test
        @DisplayName("G-5: menu_lines に request.lineId が列挙されていれば成立する")
        void 提供ライン列挙時は成立() {
            given(menuLineRepository.findByMenuId(MENU_ID)).willReturn(List.of(
                    ReservationMenuLineEntity.builder().menuId(MENU_ID).lineId(LINE_ID).build()));

            ReservationGroupResponse response =
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L)));

            assertThat(response.getSlotCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("メニュー不存在/他チームは 404=032")
        void メニュー不存在は032() {
            given(menuRepository.findByIdAndTeamId(MENU_ID, TEAM_ID)).willReturn(java.util.Optional.empty());

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.MENU_NOT_FOUND);
        }

        @Test
        @DisplayName("無効メニュー（is_active=FALSE）は 404=032")
        void 無効メニューは032() {
            given(menuRepository.findByIdAndTeamId(MENU_ID, TEAM_ID))
                    .willReturn(java.util.Optional.of(menu(60, false)));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.MENU_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("createGroup: 予約不可枠・重複（G-6 / 013）")
    class CreateGroupBlockedAndDuplicate {

        @Test
        @DisplayName("G-6: いずれかの枠が予約不可枠と overlap すると 400=009")
        void 予約不可枠overlapは009() {
            given(blockedTimeRepository.findEffectiveBetween(TEAM_ID, SLOT_DATE, SLOT_DATE, SLOT_DATE.minusDays(1)))
                    .willReturn(List.of(ReservationBlockedTimeEntity.builder()
                            .teamId(TEAM_ID)
                            .blockedDate(SLOT_DATE)
                            .startTime(LocalTime.of(10, 30))
                            .endTime(LocalTime.of(11, 0))
                            .resourceType(ReservationBlockedResourceType.TEAM)
                            .build()));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.BLOCKED_TIME_CONFLICT);
            verify(slotRepository, never()).incrementBookedCountIfAvailable(anyLong());
        }

        @Test
        @DisplayName("日跨ぎslot集合は前日開始のendsNextDay blockも回避できない")
        void 日跨ぎslot集合は前日開始blockを検出する() {
            ReservationSlotEntity nextDay = ReservationSlotEntity.builder().id(103L).teamId(TEAM_ID)
                    .slotDate(SLOT_DATE.plusDays(1)).startTime(LocalTime.of(0, 0)).endTime(LocalTime.of(0, 30))
                    .endDate(SLOT_DATE.plusDays(1)).build();
            ReservationSlotEntity overnightStart = ReservationSlotEntity.builder().id(101L).teamId(TEAM_ID)
                    .slotDate(SLOT_DATE).startTime(LocalTime.of(23, 30)).endTime(LocalTime.MIDNIGHT)
                    .endDate(SLOT_DATE.plusDays(1)).build();
            given(slotRepository.findAllById(anyIterable())).willReturn(List.of(overnightStart, nextDay));
            given(teamTimezoneResolver.resolveZone(TEAM_ID)).willReturn(ZoneId.of("Asia/Tokyo"));
            given(teamTimezoneResolver.toInstant(SLOT_DATE, LocalTime.of(23, 30), ZoneId.of("Asia/Tokyo")))
                    .willReturn(Instant.parse("2026-04-01T14:30:00Z"));
            given(teamTimezoneResolver.toInstant(SLOT_DATE.plusDays(1), LocalTime.MIDNIGHT, ZoneId.of("Asia/Tokyo")))
                    .willReturn(Instant.parse("2026-04-01T15:00:00Z"));
            given(blockedTimeRepository.findEffectiveBetween(TEAM_ID, SLOT_DATE, SLOT_DATE.plusDays(1), SLOT_DATE.minusDays(1)))
                    .willReturn(List.of(ReservationBlockedTimeEntity.builder().teamId(TEAM_ID)
                            .blockedDate(SLOT_DATE).startTime(LocalTime.of(23, 0)).endTime(LocalTime.of(0, 30))
                            .endsNextDay(true).resourceType(ReservationBlockedResourceType.TEAM).build()));

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 103L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.BLOCKED_TIME_CONFLICT);
            verify(blockedTimeRepository).findEffectiveBetween(TEAM_ID, SLOT_DATE, SLOT_DATE.plusDays(1), SLOT_DATE.minusDays(1));
        }

        @Test
        @DisplayName("同一枠×同一ユーザーの active 重複は 409=013")
        void 重複予約は013() {
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(102L), eq(USER_ID), anyList())).willReturn(true);

            BusinessException e = catchBusiness(() ->
                    service.createGroup(TEAM_ID, USER_ID, request(null, List.of(101L, 102L))));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.DUPLICATE_RESERVATION);
            verify(slotRepository, never()).incrementBookedCountIfAvailable(anyLong());
        }
    }

    @Nested
    @DisplayName("createGroup: イベント一本化（G-9 ①層）")
    class CreateGroupEvents {

        @Test
        @DisplayName("G-9: AUTO 作成で Created/Confirmed が各ちょうど 1 回（枠数を乗じない）")
        void 自動確定はイベント各1回() {
            service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L)));

            List<Object> events = capturedEvents();
            assertThat(events.stream().filter(e -> e instanceof ReservationCreatedEvent)).hasSize(1);
            assertThat(events.stream().filter(e -> e instanceof ReservationConfirmedEvent)).hasSize(1);

            ReservationConfirmedEvent confirmed = events.stream()
                    .filter(ReservationConfirmedEvent.class::isInstance)
                    .map(ReservationConfirmedEvent.class::cast)
                    .findFirst().orElseThrow();
            // slotStartAt = 先頭枠開始（リマインドは「来店の 24h/1h 前」1 セットだけ・§5.5）
            assertThat(confirmed.getSlotStartAt()).isEqualTo(LocalDateTime.of(2026, 4, 1, 10, 0));
            // slotTitle にはメニュー名を渡す（§5.2 の 8）
            assertThat(confirmed.getSlotTitle()).isEqualTo("カット");
        }

        @Test
        @DisplayName("G-7/G-9: MANUAL 作成は PENDING 維持・Created 1 回・Confirmed 0 回")
        void 手動承認はPENDING維持() {
            given(reservationPolicyService.resolveApprovalMode(eq(TEAM_ID), any(ReservationSlotEntity.class)))
                    .willReturn(ApprovalMode.MANUAL);

            ReservationGroupResponse response =
                    service.createGroup(TEAM_ID, USER_ID, request(MENU_ID, List.of(101L, 102L)));

            assertThat(response.getStatus()).isEqualTo("PENDING");
            assertThat(response.getConfirmedAt()).isNull();
            List<Object> events = capturedEvents();
            assertThat(events.stream().filter(e -> e instanceof ReservationCreatedEvent)).hasSize(1);
            assertThat(events.stream().filter(e -> e instanceof ReservationConfirmedEvent)).isEmpty();
        }
    }

    // ========================================
    // getGroup（G-12 / G-14）
    // ========================================

    @Nested
    @DisplayName("getGroup: 所有権・存在秘匿（G-12）と履歴メニュー名解決（G-14）")
    class GetGroup {

        @Test
        @DisplayName("本人はグループ詳細を取得できる")
        void 本人は取得可() {
            stubGroupRows(ReservationStatus.CONFIRMED);

            ReservationGroupResponse response = service.getGroup(TEAM_ID, GROUP_ID, USER_ID);

            assertThat(response.getGroupId()).isEqualTo(GROUP_ID);
            assertThat(response.getSlotCount()).isEqualTo(2);
            assertThat(response.getStatus()).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("ADMIN は他人のグループも取得できる")
        void 管理者は取得可() {
            stubGroupRows(ReservationStatus.CONFIRMED);

            ReservationGroupResponse response = service.getGroup(TEAM_ID, GROUP_ID, ADMIN_USER_ID);

            assertThat(response.getGroupId()).isEqualTo(GROUP_ID);
        }

        @Test
        @DisplayName("G-12: 非本人・非 ADMIN は 404=040（存在秘匿）")
        void 他人非管理者は040() {
            stubGroupRows(ReservationStatus.CONFIRMED);

            BusinessException e = catchBusiness(() -> service.getGroup(TEAM_ID, GROUP_ID, OTHER_USER_ID));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_NOT_FOUND);
        }

        @Test
        @DisplayName("G-12: 不存在/他チームの groupId は 404=040")
        void 不存在は040() {
            given(reservationRepository.findByGroupIdAndTeamIdOrderById(GROUP_ID, TEAM_ID)).willReturn(List.of());

            BusinessException e = catchBusiness(() -> service.getGroup(TEAM_ID, GROUP_ID, USER_ID));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_NOT_FOUND);
        }

        @Test
        @DisplayName("G-14: 論理削除済みメニューでも menuName が非 null で解決される（findByIdIncludingDeleted 経由)")
        void 削除済みメニュー名も解決() {
            stubGroupRows(ReservationStatus.CONFIRMED);
            ReservationMenuEntity deleted = menu(60, true);
            deleted.softDelete();
            given(menuRepository.findByIdIncludingDeleted(MENU_ID)).willReturn(java.util.Optional.of(deleted));

            ReservationGroupResponse response = service.getGroup(TEAM_ID, GROUP_ID, USER_ID);

            assertThat(response.getMenuName()).isEqualTo("カット");
        }
    }

    // ========================================
    // cancelGroup（G-8）
    // ========================================

    @Nested
    @DisplayName("cancelGroup: 一括キャンセル・締切先頭枠基準（G-8）")
    class CancelGroup {

        @Test
        @DisplayName("G-8: 締切内の本人キャンセル — 全行 CANCELLED・booked_count 復帰×N・リマインド CANCELLED 化・イベント 1 回")
        void 本人締切内キャンセル() {
            List<ReservationEntity> rows = groupRows(ReservationStatus.CONFIRMED);
            given(reservationRepository.findByGroupIdAndTeamIdOrderById(GROUP_ID, TEAM_ID)).willReturn(rows);
            ReservationReminderEntity pendingReminder = ReservationReminderEntity.builder()
                    .id(1L).reservationId(501L).remindAt(LocalDateTime.of(2026, 3, 31, 10, 0)).build();
            ReservationReminderEntity sentReminder = ReservationReminderEntity.builder()
                    .id(2L).reservationId(501L).remindAt(LocalDateTime.of(2026, 3, 1, 10, 0)).build();
            sentReminder.markSent();
            given(reminderRepository.findByReservationIdOrderByRemindAtAsc(501L))
                    .willReturn(List.of(pendingReminder, sentReminder));

            ReservationGroupCancelResponse response =
                    service.cancelGroup(TEAM_ID, GROUP_ID, USER_ID, "予定が変わりました");

            assertThat(response.status()).isEqualTo("CANCELLED");
            assertThat(response.cancelledBy()).isEqualTo("USER");
            assertThat(response.cancelledCount()).isEqualTo(2);
            assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED));
            assertThat(rows.get(0).getCancelledBy()).isEqualTo(CancelledBy.USER);

            // booked_count 復帰は既存 decrementAndReopen（throw しない void）を枠数ぶん流用（§5.1）
            verify(slotService, times(2)).decrementAndReopen(any(ReservationSlotEntity.class));
            // 未送信リマインドのみ CANCELLED 化（SENT は不変・§5.4）
            assertThat(pendingReminder.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
            assertThat(sentReminder.getStatus()).isEqualTo(ReminderStatus.SENT);
            // 本人キャンセルイベントは代表行で 1 回（§5.4）
            List<Object> events = capturedEvents();
            assertThat(events.stream().filter(e -> e instanceof ReservationCancelledByMemberEvent)).hasSize(1);
        }

        @Test
        @DisplayName("G-8: 締切超過（先頭枠開始 20 時間前）の本人キャンセルは 400=026")
        void 締切超過の本人は026() {
            reinitServiceWithClockAt(LocalDateTime.of(2026, 3, 31, 14, 0)); // 先頭枠 4/1 10:00 の 20h 前
            stubGroupRows(ReservationStatus.CONFIRMED);

            BusinessException e = catchBusiness(() ->
                    service.cancelGroup(TEAM_ID, GROUP_ID, USER_ID, null));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        }

        @Test
        @DisplayName(
                "Issue #2526 番人: 締切判定は Clock のゾーンに左右されず、同一瞬間なら結果が一致する")
        void 締切判定はClockのゾーンに左右されない() {
            // 先頭枠開始 2026-04-01 10:00 / 既定締切 24h → 締切は 2026-03-31 10:00（業務ローカル時刻）。
            // 「業務基準（JVM 既定ゾーン。実行環境に依存し得るため決め打ちしない）で見て締切の 1 分前」
            // ＝2026-03-31T09:59 を、実際の JVM 既定ゾーンで instant 化した「同一瞬間」を、
            // ゾーン設定だけが異なる 2 つの Clock（UTC / Asia+09:00）で表現する。
            Instant sameInstant = LocalDateTime.of(2026, 3, 31, 9, 59)
                    .atZone(ZoneId.systemDefault()).toInstant();

            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneOffset.UTC));
            stubGroupRows(ReservationStatus.CONFIRMED);
            ReservationGroupCancelResponse responseUtc =
                    service.cancelGroup(TEAM_ID, GROUP_ID, USER_ID, "予定が変わりました");
            assertThat(responseUtc.status()).as("UTC Clock: 締切内のためキャンセル成功するはず").isEqualTo("CANCELLED");

            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneId.of("Asia/Tokyo")));
            stubGroupRows(ReservationStatus.CONFIRMED);
            ReservationGroupCancelResponse responseTokyo =
                    service.cancelGroup(TEAM_ID, GROUP_ID, USER_ID, "予定が変わりました");
            assertThat(responseTokyo.status())
                    .as("Clock のゾーン設定が判定結果に漏れ出してはならない（同一瞬間なら UTC と同じ『締切内』判定になるはず）")
                    .isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("G-8: 締切超過でも ADMIN キャンセルは 200 相当で成功する（cancelledBy=ADMIN）")
        void 締切超過でも管理者は可() {
            reinitServiceWithClockAt(LocalDateTime.of(2026, 3, 31, 14, 0));
            stubGroupRows(ReservationStatus.CONFIRMED);

            ReservationGroupCancelResponse response =
                    service.cancelGroup(TEAM_ID, GROUP_ID, ADMIN_USER_ID, "店都合");

            assertThat(response.cancelledBy()).isEqualTo("ADMIN");
            assertThat(response.cancelledCount()).isEqualTo(2);
            // ADMIN キャンセルでは会員キャンセルイベントは発行しない（単票 cancelByAdmin と同じ）
            List<Object> events = capturedEvents();
            assertThat(events.stream().filter(e -> e instanceof ReservationCancelledByMemberEvent)).isEmpty();
        }

        @Test
        @DisplayName("G-12: 非本人・非 ADMIN のキャンセルは 404=040（存在秘匿）")
        void 他人のキャンセルは040() {
            stubGroupRows(ReservationStatus.CONFIRMED);

            BusinessException e = catchBusiness(() ->
                    service.cancelGroup(TEAM_ID, GROUP_ID, OTHER_USER_ID, null));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_NOT_FOUND);
        }

        @Test
        @DisplayName("PENDING/CONFIRMED 以外のグループのキャンセルは 400=006")
        void 完了済みキャンセルは006() {
            stubGroupRows(ReservationStatus.COMPLETED);

            BusinessException e = catchBusiness(() ->
                    service.cancelGroup(TEAM_ID, GROUP_ID, USER_ID, null));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
    }

    // ========================================
    // confirm / complete / no-show（G-7）
    // ========================================

    @Nested
    @DisplayName("グループ状態遷移: confirm / complete / no-show（G-7）")
    class GroupTransitions {

        @Test
        @DisplayName("G-7: confirm — 全行 PENDING → CONFIRMED・確定イベントは代表行で 1 回")
        void 一括confirm() {
            List<ReservationEntity> rows = groupRows(ReservationStatus.PENDING);
            given(reservationRepository.findByGroupIdAndTeamIdOrderById(GROUP_ID, TEAM_ID)).willReturn(rows);

            ReservationGroupResponse response = service.confirmGroup(TEAM_ID, GROUP_ID, ADMIN_USER_ID);

            assertThat(response.getStatus()).isEqualTo("CONFIRMED");
            assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED));
            List<Object> events = capturedEvents();
            assertThat(events.stream().filter(e -> e instanceof ReservationConfirmedEvent)).hasSize(1);
        }

        @Test
        @DisplayName("G-7: 既に CONFIRMED のグループへの confirm は 400=006")
        void 確定済みconfirmは006() {
            stubGroupRows(ReservationStatus.CONFIRMED);

            BusinessException e = catchBusiness(() -> service.confirmGroup(TEAM_ID, GROUP_ID, ADMIN_USER_ID));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        @Test
        @DisplayName("不存在グループへの confirm は 404=040")
        void 不存在confirmは040() {
            given(reservationRepository.findByGroupIdAndTeamIdOrderById(GROUP_ID, TEAM_ID)).willReturn(List.of());

            BusinessException e = catchBusiness(() -> service.confirmGroup(TEAM_ID, GROUP_ID, ADMIN_USER_ID));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.GROUP_NOT_FOUND);
        }

        @Test
        @DisplayName("complete — 全行 CONFIRMED → COMPLETED")
        void 一括complete() {
            List<ReservationEntity> rows = groupRows(ReservationStatus.CONFIRMED);
            given(reservationRepository.findByGroupIdAndTeamIdOrderById(GROUP_ID, TEAM_ID)).willReturn(rows);

            ReservationGroupResponse response = service.completeGroup(TEAM_ID, GROUP_ID, ADMIN_USER_ID);

            assertThat(response.getStatus()).isEqualTo("COMPLETED");
            assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED));
        }

        @Test
        @DisplayName("PENDING グループへの complete は 400=006（前提 CONFIRMED・§4）")
        void 未確定completeは006() {
            stubGroupRows(ReservationStatus.PENDING);

            BusinessException e = catchBusiness(() -> service.completeGroup(TEAM_ID, GROUP_ID, ADMIN_USER_ID));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        @Test
        @DisplayName("no-show — 全行 CONFIRMED → NO_SHOW")
        void 一括noShow() {
            List<ReservationEntity> rows = groupRows(ReservationStatus.CONFIRMED);
            given(reservationRepository.findByGroupIdAndTeamIdOrderById(GROUP_ID, TEAM_ID)).willReturn(rows);

            ReservationGroupResponse response = service.markGroupNoShow(TEAM_ID, GROUP_ID, ADMIN_USER_ID);

            assertThat(response.getStatus()).isEqualTo("NO_SHOW");
            assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.NO_SHOW));
        }

        @Test
        @DisplayName("PENDING グループへの no-show は 400=006（前提 CONFIRMED・§4）")
        void 未確定noShowは006() {
            stubGroupRows(ReservationStatus.PENDING);

            BusinessException e = catchBusiness(() -> service.markGroupNoShow(TEAM_ID, GROUP_ID, ADMIN_USER_ID));

            assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
    }
}
