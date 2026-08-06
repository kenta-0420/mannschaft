package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.event.ReservationConfirmedEvent;
import com.mannschaft.app.reservation.event.ReservationCreatedEvent;
import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationStatsResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationService} の単体テスト。
 * 予約のCRUD・ステータス遷移・統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationService 単体テスト")
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private ReservationLineRepository lineRepository;

    @Mock
    private ReservationSlotService slotService;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private com.mannschaft.app.common.NameResolverService nameResolverService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private com.mannschaft.app.common.AccessControlService accessControlService;

    /** 予約閲覧 view ゲート（会員 or 公開）。機能C グリッドと同一述語を共有するため専用ガードに集約された。 */
    @Mock
    private com.mannschaft.app.reservation.service.ReservationViewAccessGuard viewAccessGuard;

    @Mock
    private com.mannschaft.app.reservation.service.ReservationPolicyService reservationPolicyService;

    @Mock
    private com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository blockedTimeRepository;

    /** F03.4.5 §4 W2-2: 定期予約不可枠の active ルール参照。 */
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository
            recurringBlockedTimeRepository;

    /** 機能B: overlap 判定は純ロジックのため実インスタンスを注入（RESERVATION_009 の実 throw を実検証）。 */
    private final com.mannschaft.app.reservation.service.ReservationUnavailabilityChecker unavailabilityChecker =
            new com.mannschaft.app.reservation.service.ReservationUnavailabilityChecker();

    /** F03.4.3: 一覧のグループ要約一括解決（本テストの対象外のため mock。既定は空 Map）。 */
    @Mock
    private com.mannschaft.app.reservation.service.ReservationGroupSummaryResolver groupSummaryResolver;

    private ReservationService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long RESERVATION_ID = 10L;
    private static final Long SLOT_ID = 20L;
    private static final Long LINE_ID = 30L;

    /**
     * キャンセル締切（⑤）判定の基準時刻。枠は 2026-04-01 10:00（{@link #createAvailableSlotEntity()}）であり、
     * 既定締切 24h 前（2026-03-31 10:00）より十分前の 2026-03-01 に固定することで、
     * 既存の会員キャンセル正常系を「締切内」に保つ。締切超過テストは各 @Test 内で別 Clock を注入する。
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    /**
     * enrich/enrichList のスロット・ライン取得プラミングを共通スタブ化する。
     * 各テストはスロット/ラインを保持しないため空を返し、
     * マッパーの 3 引数オーバーロードが (entity, null, null) で呼ばれた際に
     * 既定の予約レスポンスを返すよう設定する。
     */
    @BeforeEach
    void setUpEnrichPlumbing() {
        // @InjectMocks は final Clock を mock で埋めて LocalDateTime.now(clock) が NPE になるため、
        // 固定 Clock を明示注入してサービスを生成する（ReservationSlotServiceTest と同じ作法）。
        service = new ReservationService(
                reservationRepository, slotRepository, lineRepository, slotService, reservationMapper,
                nameResolverService, eventPublisher, accessControlService, viewAccessGuard,
                reservationPolicyService, blockedTimeRepository, recurringBlockedTimeRepository,
                unavailabilityChecker,
                groupSummaryResolver,
                org.mockito.Mockito.mock(com.mannschaft.app.reservation.service.ReservationWaitlistService.class),
                // F03.4.5 §6.4: レートリミットは本テストの対象外のため素通しの mock（判定は
                // ReservationCreateRateLimiterTest / ReservationCreateRateLimitPathTest が担う）。
                org.mockito.Mockito.mock(com.mannschaft.app.reservation.service.ReservationCreateRateLimiter.class),
                FIXED_CLOCK);

        given(slotRepository.findById(any())).willReturn(Optional.empty());
        given(lineRepository.findById(any())).willReturn(Optional.empty());
        given(slotRepository.findAllById(anyIterable())).willReturn(List.of());
        given(lineRepository.findAllById(anyIterable())).willReturn(List.of());
        given(reservationMapper.toReservationResponse(any(ReservationEntity.class), any(), any()))
                .willReturn(createReservationResponse());
        given(nameResolverService.resolveUserFullNames(org.mockito.ArgumentMatchers.anyCollection()))
                .willReturn(java.util.Map.of(USER_ID, "山田 太郎"));
        given(nameResolverService.resolveUserFullName(any(Long.class))).willReturn("山田 太郎");
        // F03.4.3: 一覧のグループ要約は本テストの対象外（既定は空 Map = 単枠のみ）。
        given(groupSummaryResolver.resolve(anyList())).willReturn(java.util.Map.of());
        // 予約認可ゲートの既定スタブ: view ガードは既定で通過（何もしない mock）＝予約可。
        // 認可固有のテストでは各 @Test 内で assertCanView を throw に上書きする。
        // 承認モード解決の既定スタブ: MANUAL（＝PENDING 維持・自動確定しない）。
        // AUTO 自動確定を検証するテストでは各 @Test 内で上書きする。
        given(reservationPolicyService.resolveApprovalMode(eq(TEAM_ID), any(ReservationSlotEntity.class)))
                .willReturn(ApprovalMode.MANUAL);
        // キャンセル締切（⑤）の既定ポリシー: cancelDeadlineHours=24（エンティティ既定値）。
        // 締切超過/境界を検証するテストでは各 @Test 内で上書きする。
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(com.mannschaft.app.reservation.entity.ReservationPolicyEntity.builder()
                        .teamId(TEAM_ID)
                        .build());
    }

    /**
     * 指定した固定時刻を「現在」とする Clock を注入してサービスを再生成する。
     * キャンセル締切（⑤）の超過/境界を時刻別に検証するために使う。
     */
    private void reinitServiceWithClockAt(LocalDateTime now) {
        reinitServiceWithClock(Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));
    }

    /**
     * 任意の {@link Clock}（ゾーン込み）を明示注入してサービスを再生成する。
     * Issue #2526 のゾーン一致性番人テスト（同一瞬間・異なる Clock ゾーン）で使う。
     */
    private void reinitServiceWithClock(Clock clock) {
        service = new ReservationService(
                reservationRepository, slotRepository, lineRepository, slotService, reservationMapper,
                nameResolverService, eventPublisher, accessControlService, viewAccessGuard,
                reservationPolicyService, blockedTimeRepository, recurringBlockedTimeRepository,
                unavailabilityChecker,
                groupSummaryResolver,
                org.mockito.Mockito.mock(com.mannschaft.app.reservation.service.ReservationWaitlistService.class),
                org.mockito.Mockito.mock(com.mannschaft.app.reservation.service.ReservationCreateRateLimiter.class),
                clock);
    }

    private ReservationEntity createReservationEntity() {
        return ReservationEntity.builder()
                .reservationSlotId(SLOT_ID)
                .lineId(LINE_ID)
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .userNote("テスト備考")
                .build();
    }

    private ReservationEntity createConfirmedReservationEntity() {
        ReservationEntity entity = createReservationEntity();
        entity.confirm();
        return entity;
    }

    private ReservationSlotEntity createAvailableSlotEntity() {
        return ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .slotDate(java.time.LocalDate.of(2026, 4, 1))
                .startTime(java.time.LocalTime.of(10, 0))
                .endTime(java.time.LocalTime.of(11, 0))
                .build();
    }

    private ReservationSlotEntity createFullSlotEntity() {
        ReservationSlotEntity entity = createAvailableSlotEntity();
        entity.markFull();
        return entity;
    }

    private ReservationSlotEntity createClosedSlotEntity() {
        ReservationSlotEntity entity = createAvailableSlotEntity();
        entity.close("メンテナンス");
        return entity;
    }

    private ReservationResponse createReservationResponse() {
        return ReservationResponse.builder()
                .id(RESERVATION_ID)
                .identifier(new ReservationResponse.ReservationIdentifierDto(SLOT_ID, LINE_ID, TEAM_ID, USER_ID, null))
                .status(new ReservationResponse.ReservationStatusDto("PENDING", LocalDateTime.now(), null, null))
                .cancellation(new ReservationResponse.CancellationDto(null, null, null))
                .notes(new ReservationResponse.NotesDto("テスト備考", null))
                .audit(new ReservationResponse.ReservationAuditDto(null, null))
                .build();
    }

    // ========================================
    // listTeamReservations
    // ========================================

    @Nested
    @DisplayName("listTeamReservations")
    class ListTeamReservations {

        @Test
        @DisplayName("正常系: ステータスフィルタなしで全予約を返却する")
        void 全予約一覧_ステータスなし() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            ReservationEntity entity = createReservationEntity();
            Page<ReservationEntity> page = new PageImpl<>(List.of(entity));
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByTeamIdAndIsGroupPrimaryTrueOrderByBookedAtDesc(TEAM_ID, pageable))
                    .willReturn(page);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            Page<ReservationResponse> result = service.listTeamReservations(TEAM_ID, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            verify(reservationRepository).findByTeamIdAndIsGroupPrimaryTrueOrderByBookedAtDesc(TEAM_ID, pageable);
        }

        @Test
        @DisplayName("正常系: ステータスフィルタありで予約を返却する")
        void 全予約一覧_ステータスあり() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            ReservationEntity entity = createReservationEntity();
            Page<ReservationEntity> page = new PageImpl<>(List.of(entity));
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc(
                    TEAM_ID, ReservationStatus.PENDING, pageable)).willReturn(page);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            Page<ReservationResponse> result = service.listTeamReservations(TEAM_ID, "PENDING", pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            verify(reservationRepository).findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc(
                    TEAM_ID, ReservationStatus.PENDING, pageable);
        }
    }

    // ========================================
    // getReservation
    // ========================================

    @Nested
    @DisplayName("getReservation")
    class GetReservation {

        /** 予約所有者ではない別会員のユーザー ID（USER_ID=100 とは別）。 */
        private static final Long OTHER_USER_ID = 200L;
        /** 当該チームの管理者ユーザー ID。 */
        private static final Long ADMIN_USER_ID = 300L;

        @Test
        @DisplayName("正常系: 管理者は予約詳細を閲覧できる")
        void 予約詳細_管理者_正常取得() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            try (org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> mocked =
                         org.mockito.Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class)) {
                mocked.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserId).thenReturn(ADMIN_USER_ID);
                given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

                // When
                ReservationResponse result = service.getReservation(TEAM_ID, RESERVATION_ID);

                // Then
                assertThat(result).isNotNull();
                assertThat(result.getIdentifier().teamId()).isEqualTo(TEAM_ID);
                assertThat(result.getIdentifier().userName()).isEqualTo("山田 太郎");
            }
        }

        @Test
        @DisplayName("正常系: 予約の本人（非管理者）は自分の予約詳細を閲覧できる")
        void 予約詳細_本人_正常取得() {
            // Given: entity.userId=USER_ID(100)、閲覧者も同じ本人。管理者ではない。
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            try (org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> mocked =
                         org.mockito.Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class)) {
                mocked.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

                // When
                ReservationResponse result = service.getReservation(TEAM_ID, RESERVATION_ID);

                // Then
                assertThat(result).isNotNull();
                assertThat(result.getIdentifier().teamId()).isEqualTo(TEAM_ID);
            }
        }

        @Test
        @DisplayName("異常系: 他人（非管理者・非所有者）が予約詳細を取ると RESERVATION_PERMISSION_DENIED（403 相当）")
        void 予約詳細_他人_403() {
            // Given: entity.userId=USER_ID(100)、閲覧者は別会員 OTHER_USER_ID(200)。管理者ではない。
            ReservationEntity entity = createReservationEntity();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            try (org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> mocked =
                         org.mockito.Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class)) {
                mocked.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserId).thenReturn(OTHER_USER_ID);
                given(accessControlService.isAdminOrAbove(OTHER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

                // When / Then
                assertThatThrownBy(() -> service.getReservation(TEAM_ID, RESERVATION_ID))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
            }
        }

        @Test
        @DisplayName("正常系: スロット・ラインのサマリが付与される（管理者閲覧）")
        void 予約詳細_スロットサマリ付与() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationLineEntity line = ReservationLineEntity.builder()
                    .teamId(TEAM_ID).name("カット").build();
            ReservationResponse enriched = ReservationResponse.builder()
                    .id(RESERVATION_ID)
                    .identifier(new ReservationResponse.ReservationIdentifierDto(SLOT_ID, LINE_ID, TEAM_ID, USER_ID, null))
                    .slot(new ReservationResponse.SlotSummaryDto(
                            "カット", null,
                            java.time.LocalDate.of(2026, 4, 1),
                            java.time.LocalTime.of(10, 0),
                            java.time.LocalTime.of(11, 0)))
                    .build();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(slot));
            given(lineRepository.findById(LINE_ID)).willReturn(Optional.of(line));
            given(reservationMapper.toReservationResponse(entity, slot, line)).willReturn(enriched);

            try (org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> mocked =
                         org.mockito.Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class)) {
                mocked.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserId).thenReturn(ADMIN_USER_ID);
                given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

                // When
                ReservationResponse result = service.getReservation(TEAM_ID, RESERVATION_ID);

                // Then
                assertThat(result.getSlot()).isNotNull();
                assertThat(result.getSlot().lineName()).isEqualTo("カット");
                assertThat(result.getSlot().slotDate()).isEqualTo(java.time.LocalDate.of(2026, 4, 1));
                assertThat(result.getSlot().startTime()).isEqualTo(java.time.LocalTime.of(10, 0));
                assertThat(result.getSlot().endTime()).isEqualTo(java.time.LocalTime.of(11, 0));
                verify(reservationMapper).toReservationResponse(entity, slot, line);
            }
        }

        @Test
        @DisplayName("異常系: 予約が存在しない場合BusinessExceptionがスローされる")
        void 予約詳細_存在しない() {
            // Given: 所有権判定より前に findReservationOrThrow が NOT_FOUND を投げる（SecurityUtils 到達前）。
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getReservation(TEAM_ID, RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
        }
    }

    // ========================================
    // createReservation
    // ========================================

    @Nested
    @DisplayName("createReservation")
    class CreateReservation {

        @Test
        @DisplayName("正常系: 予約が作成される")
        void 予約作成_正常() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, "テスト備考", null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationEntity savedEntity = createReservationEntity();
            ReservationResponse response = createReservationResponse();

            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toReservationResponse(savedEntity)).willReturn(response);

            // When
            ReservationResponse result = service.createReservation(TEAM_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(any(ReservationEntity.class));
            verify(slotService).incrementAndCheckFull(slot);
        }

        @Test
        @DisplayName("Issue #2538: 他チームの枠idを渡すとSLOT_NOT_FOUND(404相当)で秘匿し、保存・在庫変動も一切起きない")
        void 予約作成_他チームの枠idはSLOT_NOT_FOUNDで秘匿() {
            // Given: OTHER_TEAM_ID(TEAM_ID とは別チーム)配下で slotId=SLOT_ID を指定。
            //        teamId スコープの finder は "その teamId に属さない slotId" を見つけられないため
            //        getSlotEntity(OTHER_TEAM_ID, SLOT_ID) は例外を投げる（findByIdAndTeamId 不一致）。
            Long otherTeamId = 999L;
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, "テスト備考", null);
            given(slotService.getSlotEntity(otherTeamId, SLOT_ID))
                    .willThrow(new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));

            // When / Then: 呼び出し元 teamId (otherTeamId) と枠の実際の帰属が食い違うため 404 秘匿。
            //              「引数を受け取っている」だけでなく実際に teamId スコープの finder に到達していることを検証する。
            assertThatThrownBy(() -> service.createReservation(otherTeamId, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);

            // 枠解決で止まっているため、在庫変動（incrementAndCheckFull）・予約行の保存は一切発生しない。
            then(slotService).should(org.mockito.Mockito.never()).incrementAndCheckFull(any());
            then(reservationRepository).should(org.mockito.Mockito.never()).save(any(ReservationEntity.class));
        }

        @Test
        @DisplayName("異常系: スロットが満席の場合SLOT_FULLエラー")
        void 予約作成_スロット満席() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = createFullSlotEntity();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);

            // When / Then
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_FULL);
        }

        @Test
        @DisplayName("異常系: スロットがクローズ済みの場合SLOT_CLOSEDエラー")
        void 予約作成_スロットクローズ() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = createClosedSlotEntity();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);

            // When / Then
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_CLOSED);
        }

        @Test
        @DisplayName("B-5: 予約不可枠と overlap する枠への予約作成は BLOCKED_TIME_CONFLICT（RESERVATION_009・400）")
        void 予約作成_予約不可枠overlap() {
            // Given: slot は 2026-04-01 10:00-11:00（createAvailableSlotEntity）。同日 TEAM 全日ブロックを設定。
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(
                    eq(TEAM_ID), eq(slot.getSlotDate())))
                    .willReturn(List.of(com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity.builder()
                            .teamId(TEAM_ID).blockedDate(slot.getSlotDate())
                            .resourceType(com.mannschaft.app.reservation.ReservationBlockedResourceType.TEAM)
                            .build()));

            // When / Then
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.BLOCKED_TIME_CONFLICT);
        }

        @Test
        @DisplayName("F03.4.2 §5.6: ライン軸枠で request.lineId != slot.lineId は SLOT_LINE_MISMATCH=RESERVATION_038（400）")
        void 予約作成_ライン軸枠の不一致は038() {
            // Given: 枠はライン 30 専用（line_id=30）だがリクエストはライン 99 を指定
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, 99L, null, null);
            ReservationSlotEntity slot = createAvailableSlotEntity().toBuilder().lineId(LINE_ID).build();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);

            // When / Then: 枠の帰属と矛盾する予約は拒否・保存されない
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_LINE_MISMATCH);
            then(reservationRepository).should(org.mockito.Mockito.never())
                    .save(any(ReservationEntity.class));
        }

        @Test
        @DisplayName("F03.4.2 §3.1: ライン軸枠では予約行の line_id が枠から自動決定される（request.lineId 省略可）")
        void 予約作成_ライン軸枠は枠のラインが自動採用() {
            // Given: 枠はライン 30 専用・リクエストは lineId 省略（null）
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, null, null, null);
            ReservationSlotEntity slot = createAvailableSlotEntity().toBuilder().lineId(LINE_ID).build();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            service.createReservation(TEAM_ID, USER_ID, request);

            // Then: 保存される予約行の line_id は枠の line_id（reservations.line_id NOT NULL と整合）
            org.mockito.ArgumentCaptor<ReservationEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(ReservationEntity.class);
            verify(reservationRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues().get(0).getLineId()).isEqualTo(LINE_ID);
        }

        @Test
        @DisplayName("F03.4.2 §5.6: ライン軸枠で request.lineId == slot.lineId は従来どおり作成される")
        void 予約作成_ライン軸枠の一致は成功() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = createAvailableSlotEntity().toBuilder().lineId(LINE_ID).build();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(createReservationEntity());
            given(reservationMapper.toReservationResponse(any(ReservationEntity.class)))
                    .willReturn(createReservationResponse());

            // When / Then
            assertThat(service.createReservation(TEAM_ID, USER_ID, request)).isNotNull();
        }

        @Test
        @DisplayName("F03.4.2 §5.6: 共通枠（slot.lineId NULL）は従来どおり request.lineId をそのまま保存（挙動後退ゼロ）")
        void 予約作成_共通枠は従来どおり() {
            // Given: 共通枠（createAvailableSlotEntity は lineId 未設定 = NULL）
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            service.createReservation(TEAM_ID, USER_ID, request);

            // Then
            org.mockito.ArgumentCaptor<ReservationEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(ReservationEntity.class);
            verify(reservationRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues().get(0).getLineId()).isEqualTo(LINE_ID);
        }

        @Test
        @DisplayName("異常系: 重複予約の場合DUPLICATE_RESERVATIONエラー")
        void 予約作成_重複() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.DUPLICATE_RESERVATION);
        }

        @Test
        @DisplayName("認可: 所属者は非公開チームでも予約できる")
        void 予約作成_所属者は予約可() {
            // Given: 非公開（false）だがユーザーは所属者（setUp の既定スタブ）
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, "テスト備考", null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationEntity savedEntity = createReservationEntity();

            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(savedEntity);

            // When
            ReservationResponse result = service.createReservation(TEAM_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(any(ReservationEntity.class));
        }

        @Test
        @DisplayName("認可: 非所属者かつ非公開（既定）の場合 RESERVATION_PERMISSION_DENIED で 403 相当")
        void 予約作成_非所属者かつ非公開は拒否() {
            // Given: view ガードが 403（非公開かつ非所属者）を投げる
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                    .given(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

            // When / Then: スロット取得より前に認可で弾く
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
            // 認可で弾かれるため永続化に到達しない
            verify(reservationRepository, org.mockito.Mockito.never()).save(any(ReservationEntity.class));
        }

        @Test
        @DisplayName("AUTO: 承認モードAUTOの場合 createReservation で即時CONFIRMED かつ ReservationConfirmedEvent 発行")
        void 予約作成_AUTOで自動確定() {
            // Given: 承認モード AUTO
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, "テスト備考", null);
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .title("カット")
                    .slotDate(java.time.LocalDate.of(2026, 4, 1))
                    .startTime(java.time.LocalTime.of(10, 0))
                    .endTime(java.time.LocalTime.of(11, 0))
                    .build();
            ReservationEntity savedEntity = createReservationEntity();

            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(savedEntity);
            given(reservationPolicyService.resolveApprovalMode(eq(TEAM_ID), eq(slot)))
                    .willReturn(ApprovalMode.AUTO);

            // When
            service.createReservation(TEAM_ID, USER_ID, request);

            // Then: confirm() が呼ばれて CONFIRMED 化されている（同一エンティティを再save）
            assertThat(savedEntity.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            // AUTO 時は initial save + confirm 後の再save の計 2 回
            verify(reservationRepository, org.mockito.Mockito.times(2)).save(any(ReservationEntity.class));

            // 発行イベントを一括捕捉する（publishEvent(Object) は単一オーバーロードのため
            // Confirmed/Created の両方が同一メソッド経由で 2 回呼ばれる）。
            org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());

            // ReservationConfirmedEvent が発行され、slotStartAt が slotDate+startTime で合成されている
            ReservationConfirmedEvent confirmedEvent = captor.getAllValues().stream()
                    .filter(ReservationConfirmedEvent.class::isInstance)
                    .map(ReservationConfirmedEvent.class::cast)
                    .findFirst().orElseThrow();
            assertThat(confirmedEvent.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(confirmedEvent.getActorUserId()).isEqualTo(USER_ID);
            assertThat(confirmedEvent.getSlotTitle()).isEqualTo("カット");
            assertThat(confirmedEvent.getSlotStartAt())
                    .isEqualTo(java.time.LocalDateTime.of(2026, 4, 1, 10, 0));

            // 既存の ReservationCreatedEvent も維持され、実効モード AUTO が渡される
            ReservationCreatedEvent createdEvent = captor.getAllValues().stream()
                    .filter(ReservationCreatedEvent.class::isInstance)
                    .map(ReservationCreatedEvent.class::cast)
                    .findFirst().orElseThrow();
            assertThat(createdEvent.getApprovalMode()).isEqualTo(ApprovalMode.AUTO);
        }

        @Test
        @DisplayName("MANUAL: 承認モードMANUALの場合 createReservation で PENDING維持・ReservationConfirmedEvent 未発行")
        void 予約作成_MANUALはPENDING維持() {
            // Given: 承認モード MANUAL（setUp 既定）
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, "テスト備考", null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationEntity savedEntity = createReservationEntity();

            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(savedEntity);

            // When
            service.createReservation(TEAM_ID, USER_ID, request);

            // Then: PENDING のまま・save は 1 回のみ
            assertThat(savedEntity.getStatus()).isEqualTo(ReservationStatus.PENDING);
            verify(reservationRepository, org.mockito.Mockito.times(1)).save(any(ReservationEntity.class));

            // ReservationConfirmedEvent は発行されない
            verify(eventPublisher, org.mockito.Mockito.never())
                    .publishEvent(any(ReservationConfirmedEvent.class));

            // ReservationCreatedEvent には実効モード MANUAL が渡される
            org.mockito.ArgumentCaptor<ReservationCreatedEvent> createdCaptor =
                    org.mockito.ArgumentCaptor.forClass(ReservationCreatedEvent.class);
            verify(eventPublisher).publishEvent(createdCaptor.capture());
            assertThat(createdCaptor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("結線: 承認モード解決を ReservationPolicyService に委譲する（枠値優先は同サービスの責務）")
        void 予約作成_承認モード解決をポリシーサービスに委譲() {
            // Given: 枠に MANUAL を持つスロット。resolveApprovalMode が枠値優先で MANUAL を返すことを結線で確認する。
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .slotDate(java.time.LocalDate.of(2026, 4, 1))
                    .startTime(java.time.LocalTime.of(10, 0))
                    .endTime(java.time.LocalTime.of(11, 0))
                    .approvalMode(ApprovalMode.MANUAL)
                    .build();
            ReservationEntity savedEntity = createReservationEntity();

            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(savedEntity);
            // 枠値優先 → MANUAL（解決ロジック自体は ReservationPolicyServiceTest で担保）
            given(reservationPolicyService.resolveApprovalMode(eq(TEAM_ID), eq(slot)))
                    .willReturn(ApprovalMode.MANUAL);

            // When
            service.createReservation(TEAM_ID, USER_ID, request);

            // Then: 解決はサービスに委譲され、その結果（MANUAL）に従い PENDING 維持
            verify(reservationPolicyService).resolveApprovalMode(eq(TEAM_ID), eq(slot));
            assertThat(savedEntity.getStatus()).isEqualTo(ReservationStatus.PENDING);
        }

        @Test
        @DisplayName("認可: view ガードが通過すれば（公開ON/所属者いずれでも）予約成立し、ゲートは共有ガードへ委譲される")
        void 予約作成_ガード通過で予約成立() {
            // Given: view ガードは既定で通過（会員/公開いずれの許可経路も同一述語 ReservationViewAccessGuard に集約済み）
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null, null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationEntity savedEntity = createReservationEntity();
            given(slotService.getSlotEntity(TEAM_ID, SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(savedEntity);

            // When
            ReservationResponse result = service.createReservation(TEAM_ID, USER_ID, request);

            // Then: ゲートは共有ガードへ委譲され（同述語再利用）、通過後に予約成立
            verify(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);
            assertThat(result).isNotNull();
            verify(reservationRepository).save(any(ReservationEntity.class));
        }
    }

    // ========================================
    // confirmReservation
    // ========================================

    @Nested
    @DisplayName("confirmReservation")
    class ConfirmReservation {

        @Test
        @DisplayName("正常系: PENDING予約が確定される")
        void 予約確定_正常() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.confirmReservation(TEAM_ID, RESERVATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }

        @Test
        @DisplayName("確定イベント: 手動承認成功時に ReservationConfirmedEvent が発行される（手動承認もリマインド対象）")
        void 予約確定_手動承認でConfirmedEvent発行() {
            // Given: PENDING 予約
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .title("カット")
                    .slotDate(java.time.LocalDate.of(2026, 4, 1))
                    .startTime(java.time.LocalTime.of(10, 0))
                    .endTime(java.time.LocalTime.of(11, 0))
                    .build();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            service.confirmReservation(TEAM_ID, RESERVATION_ID);

            // Then: ReservationConfirmedEvent が発行され、slotStartAt が合成されている
            org.mockito.ArgumentCaptor<ReservationConfirmedEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(ReservationConfirmedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            ReservationConfirmedEvent event = captor.getValue();
            assertThat(event.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(event.getActorUserId()).isEqualTo(USER_ID);
            assertThat(event.getSlotTitle()).isEqualTo("カット");
            assertThat(event.getSlotStartAt())
                    .isEqualTo(java.time.LocalDateTime.of(2026, 4, 1, 10, 0));
        }

        @Test
        @DisplayName("確定イベント: CONFIRMED 済み（確定が起きない）の場合は ReservationConfirmedEvent を発行しない")
        void 予約確定_既確定なら未発行() {
            // Given: 既に CONFIRMED（isConfirmable=false）
            ReservationEntity entity = createConfirmedReservationEntity();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When / Then: 例外でガードされ、イベントは発行されない
            assertThatThrownBy(() -> service.confirmReservation(TEAM_ID, RESERVATION_ID))
                    .isInstanceOf(BusinessException.class);
            verify(eventPublisher, org.mockito.Mockito.never())
                    .publishEvent(any(ReservationConfirmedEvent.class));
        }

        @Test
        @DisplayName("異常系: CONFIRMED予約を確定しようとするとINVALID_RESERVATION_STATUSエラー")
        void 予約確定_ステータス不正() {
            // Given
            ReservationEntity entity = createConfirmedReservationEntity();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.confirmReservation(TEAM_ID, RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
    }

    // ========================================
    // cancelByAdmin
    // ========================================

    @Nested
    @DisplayName("cancelByAdmin")
    class CancelByAdmin {

        @Test
        @DisplayName("正常系: 管理者が予約をキャンセルする")
        void 管理者キャンセル_正常() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("管理者都合", null);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.cancelByAdmin(TEAM_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(slot);
        }

        @Test
        @DisplayName("異常系: COMPLETED予約をキャンセルしようとするとINVALID_RESERVATION_STATUSエラー")
        void 管理者キャンセル_ステータス不正() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("理由", null);
            ReservationEntity entity = createReservationEntity();
            entity.complete();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.cancelByAdmin(TEAM_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        @Test
        @DisplayName("正常系: 締切（24h前）を過ぎていても管理者キャンセルは可（締切の対象外）")
        void 管理者キャンセル_締切超過でも可() {
            // 締切（2026-03-31 10:00）を大きく過ぎた時刻でも、ADMIN キャンセルは締切判定を行わず可。
            reinitServiceWithClockAt(LocalDateTime.of(2026, 4, 1, 9, 0));
            CancelReservationRequest request = new CancelReservationRequest("管理者都合", null);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.cancelByAdmin(TEAM_ID, RESERVATION_ID, request);

            // Then: 締切超過でも例外なくキャンセル成立し、枠在庫が戻る。
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(slot);
            // ADMIN は締切判定を行わないため getOrDefault は呼ばれない。
            verify(reservationPolicyService, org.mockito.Mockito.never()).getOrDefault(any());
        }
    }

    // ========================================
    // cancelByUser
    // ========================================

    @Nested
    @DisplayName("cancelByUser")
    class CancelByUser {

        @Test
        @DisplayName("正常系: ユーザーが予約をキャンセルする")
        void ユーザーキャンセル_正常() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("ユーザー都合", null);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.cancelByUser(USER_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(slot);
        }

        @Test
        @DisplayName("異常系: 予約が存在しない場合RESERVATION_NOT_FOUNDエラー")
        void ユーザーキャンセル_予約なし() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("理由", null);
            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.cancelByUser(USER_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: キャンセル不可のステータスの場合INVALID_RESERVATION_STATUSエラー")
        void ユーザーキャンセル_ステータス不正() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("理由", null);
            ReservationEntity entity = createReservationEntity();
            entity.complete();
            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.cancelByUser(USER_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        @Test
        @DisplayName("異常系: 締切（24h前）を過ぎた会員キャンセルは CANCEL_DEADLINE_PASSED（400相当）")
        void ユーザーキャンセル_締切超過() {
            // 枠開始 2026-04-01 10:00 / 既定締切 24h → 締切は 2026-03-31 10:00。
            // その 1 分後（締切超過）を現在時刻に設定する。
            reinitServiceWithClockAt(LocalDateTime.of(2026, 3, 31, 10, 1));
            CancelReservationRequest request = new CancelReservationRequest("ユーザー都合", null);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();

            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);

            // When / Then
            assertThatThrownBy(() -> service.cancelByUser(USER_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
            // 締切超過で拒否されたため、枠の在庫戻し（decrementAndReopen）は呼ばれない。
            verify(slotService, org.mockito.Mockito.never()).decrementAndReopen(any());
        }

        @Test
        @DisplayName("正常系: 締切ちょうど（境界・24h前丁度）は会員キャンセル可（isAfter=false）")
        void ユーザーキャンセル_締切境界丁度はキャンセル可() {
            // 締切ちょうど（2026-03-31 10:00）。now.isAfter(deadline) は false なのでキャンセル可。
            reinitServiceWithClockAt(LocalDateTime.of(2026, 3, 31, 10, 0));
            CancelReservationRequest request = new CancelReservationRequest("ユーザー都合", null);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.cancelByUser(USER_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(slot);
        }

        @Test
        @DisplayName("正常系: 締切内（締切の1分前）は会員キャンセル可")
        void ユーザーキャンセル_締切内はキャンセル可() {
            // 締切（2026-03-31 10:00）の 1 分前。
            reinitServiceWithClockAt(LocalDateTime.of(2026, 3, 31, 9, 59));
            CancelReservationRequest request = new CancelReservationRequest("ユーザー都合", null);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.cancelByUser(USER_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(slot);
        }

        @Test
        @DisplayName(
                "Issue #2526 番人: 締切判定は Clock のゾーンに左右されず、同一瞬間なら結果が一致する")
        void 締切判定はClockのゾーンに左右されない() {
            // 枠開始 2026-04-01 10:00 / 既定締切 24h → 締切は 2026-03-31 10:00。
            // 「業務基準（JVM 既定ゾーン。CI では UTC）で見て締切の 1 分前」＝2026-03-31T09:59 を指す
            // 同一瞬間を、ゾーン設定だけが異なる 2 つの Clock（UTC / Asia+09:00）で表現する。
            // 正しい実装（LocalDateTime.now(clock.withZone(ZoneId.systemDefault()))）なら
            // Clock 自身のゾーン設定に左右されず両方とも「締切内」でキャンセル可となるはずである。
            // バグ実装（LocalDateTime.now(clock) で Clock のゾーンをそのまま使う）だと、
            // Asia/Tokyo 側は瞬間 +9h ずれた壁時計になり「締切超過」に誤判定される。
            Instant sameInstant = LocalDateTime.of(2026, 3, 31, 9, 59).toInstant(ZoneOffset.UTC);
            CancelReservationRequest request = new CancelReservationRequest("ユーザー都合", null);

            // ① UTC ゾーンの Clock（CI の既定ゾーンと一致）
            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneOffset.UTC));
            ReservationEntity entityUtc = createReservationEntity();
            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entityUtc));
            given(reservationRepository.save(entityUtc)).willReturn(entityUtc);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(createAvailableSlotEntity());
            given(reservationMapper.toReservationResponse(entityUtc)).willReturn(createReservationResponse());
            ReservationResponse resultUtc = service.cancelByUser(USER_ID, RESERVATION_ID, request);
            assertThat(resultUtc).as("UTC Clock: 締切内のためキャンセル成功するはず").isNotNull();

            // ② Asia/Tokyo ゾーンの Clock（同じ瞬間だが Clock 自身のゾーンが +09:00）
            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneId.of("Asia/Tokyo")));
            ReservationEntity entityTokyo = createReservationEntity();
            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entityTokyo));
            given(reservationRepository.save(entityTokyo)).willReturn(entityTokyo);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(createAvailableSlotEntity());
            given(reservationMapper.toReservationResponse(entityTokyo)).willReturn(createReservationResponse());

            ReservationResponse resultTokyo = service.cancelByUser(USER_ID, RESERVATION_ID, request);
            assertThat(resultTokyo)
                    .as("Clock のゾーン設定が判定結果に漏れ出してはならない"
                            + "（同一瞬間なら UTC Clock と同じ『締切内』判定になるはず）")
                    .isNotNull();
        }
    }

    // ========================================
    // completeReservation
    // ========================================

    @Nested
    @DisplayName("completeReservation")
    class CompleteReservation {

        @Test
        @DisplayName("正常系: 予約を完了にする")
        void 予約完了_正常() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.completeReservation(TEAM_ID, RESERVATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }
    }

    // ========================================
    // markNoShow
    // ========================================

    @Nested
    @DisplayName("markNoShow")
    class MarkNoShow {

        @Test
        @DisplayName("正常系: ノーショーとしてマークする")
        void ノーショー_正常() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.markNoShow(TEAM_ID, RESERVATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }
    }

    // ========================================
    // rescheduleReservation
    // ========================================

    @Nested
    @DisplayName("rescheduleReservation")
    class RescheduleReservation {

        private static final Long NEW_SLOT_ID = 50L;

        @Test
        @DisplayName("正常系: 予約をリスケジュールする")
        void リスケジュール_正常() {
            // Given
            RescheduleRequest request = new RescheduleRequest(NEW_SLOT_ID);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity oldSlot = createAvailableSlotEntity();
            ReservationSlotEntity newSlot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(oldSlot);
            given(slotService.getSlotEntity(TEAM_ID, NEW_SLOT_ID)).willReturn(newSlot);
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.rescheduleReservation(TEAM_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(oldSlot);
            verify(slotService).incrementAndCheckFull(newSlot);
        }

        @Test
        @DisplayName("Issue #2538: 移動先が他チームの枠idだとSLOT_NOT_FOUND(404相当)で秘匿し、旧枠の残数だけ戻り新枠は一切変化しない")
        void リスケジュール_移動先が他チームの枠idはSLOT_NOT_FOUNDで秘匿() {
            // Given: 移動先 newSlotId は request 由来（利用者が任意に指定できる）。teamId スコープの
            //        finder では TEAM_ID 配下に存在しないため見つからず、404 で秘匿する。
            RescheduleRequest request = new RescheduleRequest(NEW_SLOT_ID);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity oldSlot = createAvailableSlotEntity();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(oldSlot);
            given(slotService.getSlotEntity(TEAM_ID, NEW_SLOT_ID))
                    .willThrow(new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));

            // When / Then: 「引数を受け取っている」だけでなく teamId スコープの finder に実到達していることを検証する。
            assertThatThrownBy(() -> service.rescheduleReservation(TEAM_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);

            // 旧枠は既に decrementAndReopen 済み（自チームの予約行由来で安全）だが、
            // 新枠（他チーム）は解決自体が失敗するため incrementAndCheckFull は一切呼ばれない
            // ＝他チームの枠の残数・満席状態は変化しない。予約行の reschedule も保存されない。
            verify(slotService).decrementAndReopen(oldSlot);
            then(slotService).should(org.mockito.Mockito.never()).incrementAndCheckFull(any());
            then(reservationRepository).should(org.mockito.Mockito.never()).save(entity);
        }

        @Test
        @DisplayName("異常系: 新スロットが満席の場合SLOT_FULLエラー")
        void リスケジュール_新スロット満席() {
            // Given
            RescheduleRequest request = new RescheduleRequest(NEW_SLOT_ID);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity oldSlot = createAvailableSlotEntity();
            ReservationSlotEntity newSlot = createFullSlotEntity();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(oldSlot);
            given(slotService.getSlotEntity(TEAM_ID, NEW_SLOT_ID)).willReturn(newSlot);

            // When / Then
            assertThatThrownBy(() -> service.rescheduleReservation(TEAM_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_FULL);
        }
    }

    // ========================================
    // updateAdminNote
    // ========================================

    @Nested
    @DisplayName("updateAdminNote")
    class UpdateAdminNote {

        @Test
        @DisplayName("正常系: 管理者メモが更新される")
        void 管理者メモ更新_正常() {
            // Given
            AdminNoteRequest request = new AdminNoteRequest("管理者メモ内容");
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.updateAdminNote(TEAM_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }
    }

    // ========================================
    // listReservationsBySlot
    // ========================================

    @Nested
    @DisplayName("listReservationsBySlot")
    class ListReservationsBySlot {

        @Test
        @DisplayName("正常系: スロットに紐付く予約一覧が返却される")
        void スロット別予約一覧_正常() {
            // Given
            List<ReservationEntity> entities = List.of(createReservationEntity());
            List<ReservationResponse> responses = List.of(createReservationResponse());
            given(reservationRepository.findByReservationSlotIdOrderByBookedAtAsc(SLOT_ID)).willReturn(entities);
            given(reservationMapper.toReservationResponseList(entities)).willReturn(responses);

            // When
            List<ReservationResponse> result = service.listReservationsBySlot(SLOT_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listMyReservations
    // ========================================

    @Nested
    @DisplayName("listMyReservations")
    class ListMyReservations {

        @Test
        @DisplayName("正常系: ユーザーの予約一覧が返却される")
        void マイ予約一覧_正常() {
            // Given
            List<ReservationEntity> entities = List.of(createReservationEntity());
            List<ReservationResponse> responses = List.of(createReservationResponse());
            given(reservationRepository.findByUserIdAndIsGroupPrimaryTrueOrderByBookedAtDesc(USER_ID))
                    .willReturn(entities);
            given(reservationMapper.toReservationResponseList(entities)).willReturn(responses);

            // When
            List<ReservationResponse> result = service.listMyReservations(USER_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listUpcomingReservations
    // ========================================

    @Nested
    @DisplayName("listUpcomingReservations")
    class ListUpcomingReservations {

        @Test
        @DisplayName("正常系: 直近の予約一覧が返却される")
        void 直近予約一覧_正常() {
            // Given
            List<ReservationEntity> entities = List.of(createReservationEntity());
            List<ReservationResponse> responses = List.of(createReservationResponse());
            given(reservationRepository.findUpcomingByUserId(
                    eq(USER_ID), any(LocalDate.class), any(LocalTime.class)))
                    .willReturn(entities);
            given(reservationMapper.toReservationResponseList(entities)).willReturn(responses);

            // When
            List<ReservationResponse> result = service.listUpcomingReservations(USER_ID);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("注入 Clock 基準の来店日時（日付・時刻）でリポジトリを引く")
        void 来店日時基準_Clock由来の日付時刻を渡す() {
            // Given: 固定 Clock を 2026-04-01 09:30 に設定
            reinitServiceWithClockAt(LocalDateTime.of(2026, 4, 1, 9, 30));
            given(reservationRepository.findUpcomingByUserId(
                    eq(USER_ID), any(LocalDate.class), any(LocalTime.class)))
                    .willReturn(List.of());
            given(reservationMapper.toReservationResponseList(anyList())).willReturn(List.of());

            // When
            service.listUpcomingReservations(USER_ID);

            // Then: 申込時刻ではなく「現在の日付＋時刻」で来店日時を絞り込む
            then(reservationRepository).should().findUpcomingByUserId(
                    eq(USER_ID),
                    eq(LocalDate.of(2026, 4, 1)),
                    eq(LocalTime.of(9, 30)));
        }

        @Test
        @DisplayName("Issue #2526 番人: 来店日時の絞り込み基準は Clock のゾーンに左右されない")
        void 来店日時基準はClockのゾーンに左右されない() {
            // 「業務基準（JVM 既定ゾーン。CI では UTC）で見て 2026-04-01 09:30」を指す同一瞬間を、
            // ゾーン設定だけが異なる 2 つの Clock（UTC / Asia+09:00）で表現する。
            Instant sameInstant = LocalDateTime.of(2026, 4, 1, 9, 30).toInstant(ZoneOffset.UTC);
            given(reservationRepository.findUpcomingByUserId(
                    eq(USER_ID), any(LocalDate.class), any(LocalTime.class)))
                    .willReturn(List.of());
            given(reservationMapper.toReservationResponseList(anyList())).willReturn(List.of());

            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneOffset.UTC));
            service.listUpcomingReservations(USER_ID);

            reinitServiceWithClock(Clock.fixed(sameInstant, ZoneId.of("Asia/Tokyo")));
            service.listUpcomingReservations(USER_ID);

            // 両呼び出しとも、Clock のゾーンに関わらず同一の日付・時刻でクエリされているはず。
            then(reservationRepository).should(times(2)).findUpcomingByUserId(
                    eq(USER_ID),
                    eq(LocalDate.of(2026, 4, 1)),
                    eq(LocalTime.of(9, 30)));
        }
    }

    // ========================================
    // getStats
    // ========================================

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: チームの予約統計が正しく集計される")
        void 統計取得_正常() {
            // Given
            given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.PENDING)).willReturn(5L);
            given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.CONFIRMED)).willReturn(10L);
            given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.CANCELLED)).willReturn(2L);
            given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.COMPLETED)).willReturn(20L);
            given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.NO_SHOW)).willReturn(1L);

            // When
            ReservationStatsResponse result = service.getStats(TEAM_ID);

            // Then
            assertThat(result.getTotalReservations()).isEqualTo(38L);
            assertThat(result.getPendingCount()).isEqualTo(5L);
            assertThat(result.getConfirmedCount()).isEqualTo(10L);
            assertThat(result.getCancelledCount()).isEqualTo(2L);
            assertThat(result.getCompletedCount()).isEqualTo(20L);
            assertThat(result.getNoShowCount()).isEqualTo(1L);
        }
    }
}
