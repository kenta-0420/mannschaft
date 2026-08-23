package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationSlotService} の単体テスト。
 * 予約スロットのCRUD・状態管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationSlotService 単体テスト")
class ReservationSlotServiceTest {

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository blockedTimeRepository;

    /** F03.4.5 §4 W2-2: 定期予約不可枠の active ルール参照。 */
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;

    /** F03.4.2: 枠のライン軸（lineId）検証用のライン参照。 */
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationLineRepository lineRepository;

    /** 予約閲覧の view ゲート（会員 or 公開）。デフォルトのモック（void）は常に通過する。 */
    @Mock
    private com.mannschaft.app.reservation.service.ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private TeamTimezoneResolver teamTimezoneResolver;

    /** 機能B: overlap 判定は純ロジックのため実インスタンスを注入（listAvailableSlots の除外挙動を実検証）。 */
    private final com.mannschaft.app.reservation.service.ReservationUnavailabilityChecker unavailabilityChecker =
            new com.mannschaft.app.reservation.service.ReservationUnavailabilityChecker();

    private ReservationSlotService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 5L;
    private static final Long SLOT_ID = 10L;
    private static final Long STAFF_USER_ID = 50L;
    private static final Long CREATED_BY = 100L;
    private static final LocalDate SLOT_DATE = LocalDate.of(2026, 4, 1);
    private static final LocalTime START_TIME = LocalTime.of(10, 0);
    private static final LocalTime END_TIME = LocalTime.of(11, 0);

    /** 過去日判定の基準を SLOT_DATE（2026-04-01）より前の固定時刻に置く（既存正常系を未来日のまま保つ）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        // @InjectMocks は Clock を mock で埋めてしまい LocalDate.now(clock) が NPE になるため、
        // 固定 Clock を明示注入してサービスを生成する。
        service = new ReservationSlotService(slotRepository, reservationRepository, reservationMapper,
                blockedTimeRepository, recurringBlockedTimeRepository, unavailabilityChecker, lineRepository,
                FIXED_CLOCK,
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                viewAccessGuard, teamTimezoneResolver);
        given(teamTimezoneResolver.resolveZone(TEAM_ID)).willReturn(ZoneId.of("UTC"));
    }

    private ReservationSlotEntity createSlotEntity() {
        return ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .staffUserId(STAFF_USER_ID)
                .title("テストスロット")
                .slotDate(SLOT_DATE)
                .startTime(START_TIME)
                .endTime(END_TIME)
                .price(new BigDecimal("1000"))
                .note("テストメモ")
                .createdBy(CREATED_BY)
                .build();
    }

    private ReservationSlotResponse createSlotResponse() {
        return ReservationSlotResponse.builder()
                .id(SLOT_ID)
                .teamId(TEAM_ID)
                .staffUserId(STAFF_USER_ID)
                .basic(new ReservationSlotResponse.SlotBasicDto("テストスロット", SLOT_DATE, START_TIME, END_TIME))
                .status(new ReservationSlotResponse.SlotStatusDto("AVAILABLE", 0, 1, null, "テストメモ"))
                .pricing(new ReservationSlotResponse.SlotPricingDto(new BigDecimal("1000")))
                .policy(new ReservationSlotResponse.SlotPolicyDto(null))
                .audit(new ReservationSlotResponse.SlotAuditDto(CREATED_BY, null, null))
                .build();
    }

    // ========================================
    // listSlots
    // ========================================

    @Nested
    @DisplayName("listSlots")
    class ListSlots {

        @Test
        @DisplayName("正常系: チームのスロット一覧を日付範囲で取得する")
        void スロット一覧_正常() {
            // Given
            LocalDate from = SLOT_DATE;
            LocalDate to = SLOT_DATE.plusDays(7);
            List<ReservationSlotEntity> entities = List.of(createSlotEntity());
            List<ReservationSlotResponse> responses = List.of(createSlotResponse());
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, from, to))
                    .willReturn(entities);
            given(reservationMapper.toSlotResponseList(entities)).willReturn(responses);

            // When
            List<ReservationSlotResponse> result = service.listSlots(TEAM_ID, USER_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBasic().title()).isEqualTo("テストスロット");
        }
    }

    // ========================================
    // listAvailableSlots
    // ========================================

    @Nested
    @DisplayName("listAvailableSlots")
    class ListAvailableSlots {

        @Test
        @DisplayName("正常系: 利用可能なスロット一覧を取得する")
        void 利用可能スロット一覧_正常() {
            // Given
            LocalDate from = SLOT_DATE;
            LocalDate to = SLOT_DATE.plusDays(7);
            List<ReservationSlotEntity> entities = List.of(createSlotEntity());
            List<ReservationSlotResponse> responses = List.of(createSlotResponse());
            given(slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    TEAM_ID, SlotStatus.AVAILABLE, from, to)).willReturn(entities);
            given(reservationMapper.toSlotResponseList(entities)).willReturn(responses);

            // When
            List<ReservationSlotResponse> result = service.listAvailableSlots(TEAM_ID, USER_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // 機能B: listAvailableSlots からの予約不可枠除外（§5.B / 受け入れ条件 B-1〜B-4・B-8）
    // ========================================

    @Nested
    @DisplayName("listAvailableSlots 機能B 予約不可枠除外")
    class ListAvailableSlotsUnavailability {

        private final LocalDate from = SLOT_DATE;
        private final LocalDate to = SLOT_DATE.plusDays(7);

        /** 指定 staff・時間帯の AVAILABLE slot を組み立てる。 */
        private ReservationSlotEntity slot(Long staffUserId, LocalTime start, LocalTime end) {
            return ReservationSlotEntity.builder()
                    .teamId(TEAM_ID).staffUserId(staffUserId).slotDate(SLOT_DATE)
                    .startTime(start).endTime(end).build();
        }

        private com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity block(
                com.mannschaft.app.reservation.ReservationBlockedResourceType type, Long resourceId,
                LocalTime start, LocalTime end) {
            return com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity.builder()
                    .teamId(TEAM_ID).blockedDate(SLOT_DATE)
                    .startTime(start).endTime(end)
                    .resourceType(type).resourceId(resourceId).build();
        }

        /** listAvailableSlots が mapper に渡す（＝除外後の）slot リストを捕捉する。 */
        @SuppressWarnings("unchecked")
        private List<ReservationSlotEntity> captureVisibleSlots() {
            org.mockito.ArgumentCaptor<List<ReservationSlotEntity>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            given(reservationMapper.toSlotResponseList(captor.capture())).willReturn(List.of());
            service.listAvailableSlots(TEAM_ID, USER_ID, from, to);
            return captor.getValue();
        }

        @Test
        @DisplayName("B-1: TEAM 予約不可枠がある日は当日の全 slot が除外される")
        void B1_TEAM全日除外() {
            List<ReservationSlotEntity> slots = List.of(
                    slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                    slot(60L, LocalTime.of(11, 0), LocalTime.of(12, 0)),
                    slot(null, LocalTime.of(12, 0), LocalTime.of(13, 0)));
            given(slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    TEAM_ID, SlotStatus.AVAILABLE, from, to)).willReturn(slots);
            given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                    TEAM_ID, from, to)).willReturn(List.of(
                    block(com.mannschaft.app.reservation.ReservationBlockedResourceType.TEAM, null, null, null)));

            assertThat(captureVisibleSlots()).isEmpty();
        }

        @Test
        @DisplayName("B-2: STAFF 予約不可枠は対象スタッフの slot のみ除外し他スタッフ/共通は残す")
        void B2_STAFF軸のみ除外() {
            ReservationSlotEntity target = slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0));
            ReservationSlotEntity otherStaff = slot(60L, LocalTime.of(10, 0), LocalTime.of(11, 0));
            ReservationSlotEntity common = slot(null, LocalTime.of(10, 0), LocalTime.of(11, 0));
            given(slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    TEAM_ID, SlotStatus.AVAILABLE, from, to)).willReturn(List.of(target, otherStaff, common));
            given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                    TEAM_ID, from, to)).willReturn(List.of(
                    block(com.mannschaft.app.reservation.ReservationBlockedResourceType.STAFF, 50L, null, null)));

            assertThat(captureVisibleSlots()).containsExactly(otherStaff, common);
        }

        @Test
        @DisplayName("B-3: 部分ブロック[10:00,11:00]は該当slotを除外し隣接[11:00,12:00]は残す（半開区間）")
        void B3_部分ブロック半開境界() {
            ReservationSlotEntity blocked = slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0));
            ReservationSlotEntity adjacent = slot(50L, LocalTime.of(11, 0), LocalTime.of(12, 0));
            given(slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    TEAM_ID, SlotStatus.AVAILABLE, from, to)).willReturn(List.of(blocked, adjacent));
            given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                    TEAM_ID, from, to)).willReturn(List.of(
                    block(com.mannschaft.app.reservation.ReservationBlockedResourceType.TEAM, null,
                            LocalTime.of(10, 0), LocalTime.of(11, 0))));

            assertThat(captureVisibleSlots()).containsExactly(adjacent);
            verify(teamTimezoneResolver).resolveZone(TEAM_ID);
        }

        @Test
        @DisplayName("B-8: 既存行（resourceType=TEAM/resourceId=null＝ALTER前互換）は全 slot 対象として判定される")
        void B8_後方互換TEAM() {
            ReservationSlotEntity s1 = slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0));
            ReservationSlotEntity s2 = slot(null, LocalTime.of(11, 0), LocalTime.of(12, 0));
            given(slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    TEAM_ID, SlotStatus.AVAILABLE, from, to)).willReturn(List.of(s1, s2));
            // ALTER 前データを模した TEAM/null 全日枠。
            given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                    TEAM_ID, from, to)).willReturn(List.of(
                    block(com.mannschaft.app.reservation.ReservationBlockedResourceType.TEAM, null, null, null)));

            assertThat(captureVisibleSlots()).isEmpty();
        }
    }

    // ========================================
    // getSlot
    // ========================================

    @Nested
    @DisplayName("getSlot")
    class GetSlot {

        @Test
        @DisplayName("正常系: スロット詳細が返却される")
        void スロット詳細_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationMapper.toSlotResponse(entity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.getSlot(TEAM_ID, USER_ID, SLOT_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getBasic().title()).isEqualTo("テストスロット");
        }

        @Test
        @DisplayName("異常系: スロットが存在しない場合SLOT_NOT_FOUNDエラー")
        void スロット詳細_存在しない() {
            // Given
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getSlot(TEAM_ID, USER_ID, SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
        }
    }

    // ========================================
    // createSlot
    // ========================================

    @Nested
    @DisplayName("createSlot")
    class CreateSlot {

        @Test
        @DisplayName("正常系: スロットが作成される")
        void スロット作成_正常() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "新スロット", SLOT_DATE, START_TIME, END_TIME,
                    null, new BigDecimal("2000"), "メモ", null, null);
            ReservationSlotEntity savedEntity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.save(any(ReservationSlotEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toSlotResponse(savedEntity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ReservationSlotEntity.class));
        }

        @Test
        @DisplayName("正常系: approvalMode=MANUAL を指定すると枠の上書きとして entity に保存される")
        void スロット作成_承認モード上書き() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "手動承認枠", SLOT_DATE, START_TIME, END_TIME,
                    null, null, null, ApprovalMode.MANUAL, null);
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then: 保存される entity の approvalMode が MANUAL であること
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("正常系: approvalMode 未指定（null）なら entity は NULL のまま（チーム既定を継承）")
        void スロット作成_承認モード未指定は継承() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "継承枠", SLOT_DATE, START_TIME, END_TIME,
                    null, null, null, null, null);
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then: approvalMode は NULL（継承）
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isNull();
        }

        // F03.4.2: ライン軸（lineId）の付与と検証
        @Test
        @DisplayName("F03.4.2 F-1系: lineId 指定でライン軸枠として保存される（active ライン検証つき）")
        void スロット作成_ライン軸() {
            // Given
            Long lineId = 30L;
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "席1枠", SLOT_DATE, START_TIME, END_TIME,
                    lineId, null, null, null, null);
            given(lineRepository.findByIdAndTeamId(lineId, TEAM_ID))
                    .willReturn(Optional.of(com.mannschaft.app.reservation.entity.ReservationLineEntity.builder()
                            .teamId(TEAM_ID).name("席1").build()));
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getLineId()).isEqualTo(lineId);
        }

        @Test
        @DisplayName("F03.4.2: 不正 lineId（他チーム/不存在）は LINE_NOT_FOUND=001（400）で保存されない")
        void スロット作成_不正ラインは001() {
            // Given
            Long lineId = 999L;
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "不正ライン枠", SLOT_DATE, START_TIME, END_TIME,
                    lineId, null, null, null, null);
            given(lineRepository.findByIdAndTeamId(lineId, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
            verify(slotRepository, never()).save(any(ReservationSlotEntity.class));
        }

        @Test
        @DisplayName("F-11/F-12: lineId 未指定（共通枠）は従来どおり作成され、recurrenceRule は保存経路ごと廃止されている")
        void スロット作成_共通枠は従来どおり() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "共通枠", SLOT_DATE, START_TIME, END_TIME,
                    null, null, null, null, null);
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then: lineId=NULL（共通枠・既存互換）
            // ※ recurrenceRule は F03.4.2 §3.3 のクリーンアップで列・フィールドごと撤去済み
            //   （復活検出は ReservationSlotUnusedColumnRemovalTest が担う）
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getLineId()).isNull();
        }

        @Test
        @DisplayName("異常系: 開始時刻が終了時刻以降の場合INVALID_TIME_RANGEエラー")
        void スロット作成_時刻逆転() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "不正スロット", SLOT_DATE,
                    LocalTime.of(14, 0), LocalTime.of(10, 0),
                    null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: 開始時刻と終了時刻が同一の場合INVALID_TIME_RANGEエラー")
        void スロット作成_時刻同一() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "不正スロット", SLOT_DATE,
                    LocalTime.of(10, 0), LocalTime.of(10, 0),
                    null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        // ② 30分単位バリデーション
        @Test
        @DisplayName("異常系: 開始時刻が30分グリッドに乗らない（10:15）場合INVALID_SLOT_GRANULARITY（400）")
        void スロット作成_開始15分刻み() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "不正グリッド", SLOT_DATE,
                    LocalTime.of(10, 15), LocalTime.of(11, 0),
                    null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_SLOT_GRANULARITY);
        }

        @Test
        @DisplayName("異常系: 終了時刻が30分グリッドに乗らない（10:00→10:45）場合INVALID_SLOT_GRANULARITY（400）")
        void スロット作成_終了15分刻み() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "不正グリッド", SLOT_DATE,
                    LocalTime.of(10, 0), LocalTime.of(10, 45),
                    null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_SLOT_GRANULARITY);
        }

        @Test
        @DisplayName("異常系: 枠長が30分未満（10:00→10:15はグリッド外、10:00→10:00は逆転）でない最小枠未満境界を検証")
        void スロット作成_最小枠未満() {
            // Given: 10:00→10:15 は分が15で既にグリッド外だが、グリッドに乗った 30 分未満は構成不能（00/30 刻みで最小差は 30 分）。
            // よって 30 分グリッドに乗りつつ 30 分未満となるケースは存在しない＝グリッド検証で最小枠が担保される。
            // ここでは「30分ちょうど（10:00→10:30）」が許可されることを正常系として確認する。
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "最小枠30分", SLOT_DATE,
                    LocalTime.of(10, 0), LocalTime.of(10, 30),
                    null, null, null, null, null);
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When / Then: 例外なく作成される
            ReservationSlotResponse result = service.createSlot(TEAM_ID, request, CREATED_BY);
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ReservationSlotEntity.class));
        }

        // ③ 過去日チェック（固定 Clock = 2026-03-01）
        @Test
        @DisplayName("異常系: 過去日（2026-02-28 < Clock=2026-03-01）の枠作成はPAST_DATE_SLOT（400）")
        void スロット作成_過去日() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "過去枠", LocalDate.of(2026, 2, 28),
                    START_TIME, END_TIME, null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.PAST_DATE_SLOT);
        }

        @Test
        @DisplayName("正常系: 当日（Clock=2026-03-01）の枠作成は許可される（過去日チェック境界）")
        void スロット作成_当日許可() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "当日枠", LocalDate.of(2026, 3, 1),
                    START_TIME, END_TIME, null, null, null, null, null);
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When / Then
            ReservationSlotResponse result = service.createSlot(TEAM_ID, request, CREATED_BY);
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updateSlot
    // ========================================

    @Nested
    @DisplayName("updateSlot")
    class UpdateSlot {

        @Test
        @DisplayName("正常系: スロットが部分更新される")
        void スロット更新_正常() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, "更新後タイトル", null, null, null, null, null, null, null, null, null);
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class))).willReturn(entity);
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class))).willReturn(response);

            // When
            ReservationSlotResponse result = service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ReservationSlotEntity.class));
        }

        @Test
        @DisplayName("正常系: 時間帯を含む更新が正常に処理される")
        void スロット更新_時間帯変更() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, LocalTime.of(9, 0), LocalTime.of(12, 0), null, null, null, null, null, null);
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class))).willReturn(entity);
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class))).willReturn(response);

            // When
            ReservationSlotResponse result = service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 更新時の時刻が逆転している場合INVALID_TIME_RANGEエラー")
        void スロット更新_時刻逆転() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, LocalTime.of(14, 0), LocalTime.of(10, 0), null, null, null, null, null, null);
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.updateSlot(TEAM_ID, SLOT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("正常系: approvalMode=MANUAL 指定で枠の上書きが設定される")
        void スロット更新_承認モード上書き設定() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, null, null, null, null, null, ApprovalMode.MANUAL, null, null);
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("正常系: clearApprovalMode=true で上書きが解除され NULL（チーム既定継承）に戻る")
        void スロット更新_承認モード上書き解除() {
            // Given: 既に MANUAL で上書きされている枠
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, null, null, null, null, null, null, true, null);
            ReservationSlotEntity entity = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .slotDate(SLOT_DATE)
                    .startTime(START_TIME)
                    .endTime(END_TIME)
                    .approvalMode(ApprovalMode.MANUAL)
                    .build();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then: approvalMode は NULL に戻る
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isNull();
        }

        @Test
        @DisplayName("正常系: approvalMode/clearApprovalMode いずれも未指定なら既存の上書き値が据え置かれる")
        void スロット更新_承認モード据え置き() {
            // Given: MANUAL で上書き済みの枠を、approvalMode 非指定で更新
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, "タイトルだけ変更", null, null, null, null, null, null, null, null, null);
            ReservationSlotEntity entity = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .slotDate(SLOT_DATE)
                    .startTime(START_TIME)
                    .endTime(END_TIME)
                    .approvalMode(ApprovalMode.MANUAL)
                    .build();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then: 据え置き（MANUAL のまま）
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
        }
    }

    // ========================================
    // deleteSlot
    // ========================================

    @Nested
    @DisplayName("deleteSlot")
    class DeleteSlot {

        @Test
        @DisplayName("正常系: active予約のないスロットは論理削除される")
        void スロット削除_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), anyList()))
                    .willReturn(false);

            // When
            service.deleteSlot(TEAM_ID, SLOT_ID);

            // Then
            verify(slotRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: スロットが存在しない場合SLOT_NOT_FOUNDエラー")
        void スロット削除_存在しない() {
            // Given
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteSlot(TEAM_ID, SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: active予約が紐づくスロットの削除はSLOT_HAS_ACTIVE_RESERVATIONS（409）で拒否され、論理削除されない")
        void スロット削除_active予約あり() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), anyList()))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.deleteSlot(TEAM_ID, SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_HAS_ACTIVE_RESERVATIONS);

            // オーファン化防止: 削除（save）は呼ばれない
            verify(slotRepository, never()).save(any(ReservationSlotEntity.class));
        }

        @Test
        @DisplayName("正常系: ガードが参照する active ステータスは PENDING / CONFIRMED の2件のみ")
        void スロット削除_ガード対象ステータス() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), anyList()))
                    .willReturn(false);

            // When
            service.deleteSlot(TEAM_ID, SLOT_ID);

            // Then: 終端状態（CANCELLED/COMPLETED/NO_SHOW）は削除を妨げないことを、
            //       ガードに渡されるステータス集合で検証する
            ArgumentCaptor<List<ReservationStatus>> captor = ArgumentCaptor.forClass(List.class);
            verify(reservationRepository).existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), captor.capture());
            assertThat(captor.getValue())
                    .containsExactlyInAnyOrder(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
        }
    }

    // ========================================
    // closeSlot
    // ========================================

    @Nested
    @DisplayName("closeSlot")
    class CloseSlot {

        @Test
        @DisplayName("正常系: スロットがクローズされる")
        void スロットクローズ_正常() {
            // Given
            CloseSlotRequest request = new CloseSlotRequest("メンテナンス");
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toSlotResponse(entity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.closeSlot(TEAM_ID, SLOT_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(entity);
        }
    }

    // ========================================
    // reopenSlot
    // ========================================

    @Nested
    @DisplayName("reopenSlot")
    class ReopenSlot {

        @Test
        @DisplayName("正常系: スロットが再開される")
        void スロット再開_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            entity.close("メンテナンス");
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toSlotResponse(entity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.reopenSlot(TEAM_ID, SLOT_ID);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(entity);
        }
    }

    // ========================================
    // listSlotsByStaff
    // ========================================

    @Nested
    @DisplayName("listSlotsByStaff")
    class ListSlotsByStaff {

        @Test
        @DisplayName("正常系: 担当者のスロット一覧が返却される")
        void 担当者スロット一覧_正常() {
            // Given
            LocalDate from = SLOT_DATE;
            LocalDate to = SLOT_DATE.plusDays(7);
            List<ReservationSlotEntity> entities = List.of(createSlotEntity());
            List<ReservationSlotResponse> responses = List.of(createSlotResponse());
            given(slotRepository.findByStaffUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    STAFF_USER_ID, from, to)).willReturn(entities);
            given(reservationMapper.toSlotResponseList(entities)).willReturn(responses);

            // When
            List<ReservationSlotResponse> result = service.listSlotsByStaff(STAFF_USER_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getSlotEntity
    // ========================================

    @Nested
    @DisplayName("getSlotEntity")
    class GetSlotEntity {

        @Test
        @DisplayName("正常系: スロットエンティティが返却される")
        void スロットエンティティ取得_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));

            // When
            ReservationSlotEntity result = service.getSlotEntity(SLOT_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("異常系: スロットが存在しない場合SLOT_NOT_FOUNDエラー")
        void スロットエンティティ取得_存在しない() {
            // Given
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getSlotEntity(SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
        }
    }

    // ========================================
    // incrementAndCheckFull
    // ========================================

    @Nested
    @DisplayName("incrementAndCheckFull（オーバーブッキング防止・条件付きアトミック UPDATE）")
    class IncrementAndCheckFull {

        @Test
        @DisplayName("正常系: 確保成功（UPDATE 1 行）なら例外を投げない")
        void インクリメント_確保成功() {
            // Given: 条件付きアトミック UPDATE が 1 行更新（＝空き枠を確保できた）
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.incrementBookedCountIfAvailable(any())).willReturn(1);

            // When / Then: 例外なく完了し、アトミック UPDATE が呼ばれる
            service.incrementAndCheckFull(entity);
            verify(slotRepository).incrementBookedCountIfAvailable(any());
        }

        @Test
        @DisplayName("異常系: 満席で 0 行更新なら SLOT_FULL（オーバーブッキング拒否）")
        void インクリメント_満席拒否() {
            // Given: 条件付きアトミック UPDATE が 0 行更新（＝満席 or CLOSED で確保できない）
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.incrementBookedCountIfAvailable(any())).willReturn(0);

            // When / Then: SLOT_FULL を投げる（呼び出し元の予約 INSERT ごとロールバックさせる）
            assertThatThrownBy(() -> service.incrementAndCheckFull(entity))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_FULL);
        }
    }

    // ========================================
    // decrementAndReopen
    // ========================================

    @Nested
    @DisplayName("decrementAndReopen（アトミック UPDATE）")
    class DecrementAndReopen {

        @Test
        @DisplayName("正常系: デクリメント → reopen 専用 UPDATE の順で呼ばれる（発火判定はDB遷移事実）")
        void デクリメント() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();

            // When
            service.decrementAndReopen(entity);

            // Then: booked_count 減算 → FULL→AVAILABLE 遷移ゲート（affected-rows）の 2 段で呼ばれる（F03.4.5 §6.1 根治）
            verify(slotRepository).decrementBookedCount(any());
            verify(slotRepository).reopenSlotIfFull(any());
        }
    }
}
