package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourEntry;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateOutcome;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.service.ReservationBusinessHourService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationBusinessHourService} の単体テスト。
 * 営業時間・ブロック時間の管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationBusinessHourService 単体テスト")
class ReservationBusinessHourServiceTest {

    @Mock
    private ReservationBusinessHourRepository businessHourRepository;

    @Mock
    private ReservationBlockedTimeRepository blockedTimeRepository;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private com.mannschaft.app.reservation.repository.ReservationRepository reservationRepository;

    @Mock
    private com.mannschaft.app.reservation.repository.ReservationSlotRepository slotRepository;

    @Mock
    private com.mannschaft.app.common.NameResolverService nameResolverService;

    /** 予約閲覧の view ゲート（会員 or 公開）。デフォルトのモック（void）は常に通過する。 */
    @Mock
    private com.mannschaft.app.reservation.service.ReservationViewAccessGuard viewAccessGuard;

    @InjectMocks
    private ReservationBusinessHourService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 5L;
    private static final Long BLOCKED_ID = 10L;
    private static final Long CREATED_BY = 100L;

    private ReservationBusinessHourEntity createBusinessHourEntity() {
        return ReservationBusinessHourEntity.builder()
                .teamId(TEAM_ID)
                .dayOfWeek("MON")
                .isOpen(true)
                .openTime(LocalTime.of(9, 0))
                .closeTime(LocalTime.of(18, 0))
                .build();
    }

    private BusinessHourResponse createBusinessHourResponse() {
        return BusinessHourResponse.builder()
                .id(1L)
                .teamId(TEAM_ID)
                .businessStatus(new BusinessHourResponse.BusinessStatusDto("MON", true, LocalTime.of(9, 0), LocalTime.of(18, 0)))
                .build();
    }

    private ReservationBlockedTimeEntity createBlockedTimeEntity() {
        return ReservationBlockedTimeEntity.builder()
                .teamId(TEAM_ID)
                .blockedDate(LocalDate.of(2026, 4, 1))
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(13, 0))
                .reason("昼休み")
                .createdBy(CREATED_BY)
                .build();
    }

    private BlockedTimeResponse createBlockedTimeResponse() {
        return BlockedTimeResponse.builder()
                .id(BLOCKED_ID)
                .teamId(TEAM_ID)
                .timeSlot(new BlockedTimeResponse.TimeSlotDto(LocalDate.of(2026, 4, 1), LocalTime.of(12, 0), LocalTime.of(13, 0)))
                .audit(new BlockedTimeResponse.BlockedAuditDto("昼休み", CREATED_BY, null, null))
                .build();
    }

    // ========================================
    // getBusinessHours
    // ========================================

    @Nested
    @DisplayName("getBusinessHours")
    class GetBusinessHours {

        @Test
        @DisplayName("正常系: チームの営業時間設定が返却される")
        void 営業時間取得_正常() {
            // Given
            List<ReservationBusinessHourEntity> entities = List.of(createBusinessHourEntity());
            List<BusinessHourResponse> responses = List.of(createBusinessHourResponse());
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID)).willReturn(entities);
            given(reservationMapper.toBusinessHourResponseList(entities)).willReturn(responses);

            // When
            List<BusinessHourResponse> result = service.getBusinessHours(TEAM_ID, USER_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBusinessStatus().dayOfWeek()).isEqualTo("MON");
        }
    }

    // ========================================
    // updateBusinessHours
    // ========================================

    @Nested
    @DisplayName("updateBusinessHours")
    class UpdateBusinessHours {

        @Test
        @DisplayName("正常系: 既存の営業時間が更新される")
        void 営業時間更新_既存更新() {
            // Given
            BusinessHourEntry entry = new BusinessHourEntry(
                    "MON", true, LocalTime.of(10, 0), LocalTime.of(19, 0));
            BusinessHoursUpdateRequest request = new BusinessHoursUpdateRequest(List.of(entry));
            ReservationBusinessHourEntity existingEntity = createBusinessHourEntity();
            BusinessHourResponse response = createBusinessHourResponse();

            given(businessHourRepository.findByTeamIdAndDayOfWeek(TEAM_ID, "MON"))
                    .willReturn(Optional.of(existingEntity));
            given(businessHourRepository.save(existingEntity)).willReturn(existingEntity);
            given(reservationMapper.toBusinessHourResponseList(any())).willReturn(List.of(response));

            // When
            BusinessHoursUpdateOutcome result = service.updateBusinessHours(TEAM_ID, request);

            // Then
            assertThat(result.hours()).hasSize(1);
            verify(businessHourRepository).save(existingEntity);
        }

        @Test
        @DisplayName("正常系: 新規の営業時間が作成される")
        void 営業時間更新_新規作成() {
            // Given
            BusinessHourEntry entry = new BusinessHourEntry(
                    "TUE", true, LocalTime.of(9, 0), LocalTime.of(17, 0));
            BusinessHoursUpdateRequest request = new BusinessHoursUpdateRequest(List.of(entry));
            ReservationBusinessHourEntity newEntity = ReservationBusinessHourEntity.builder()
                    .teamId(TEAM_ID)
                    .dayOfWeek("TUE")
                    .isOpen(true)
                    .openTime(LocalTime.of(9, 0))
                    .closeTime(LocalTime.of(17, 0))
                    .build();
            BusinessHourResponse response = BusinessHourResponse.builder()
                    .id(2L)
                    .teamId(TEAM_ID)
                    .businessStatus(new BusinessHourResponse.BusinessStatusDto("TUE", true, LocalTime.of(9, 0), LocalTime.of(17, 0)))
                    .build();

            given(businessHourRepository.findByTeamIdAndDayOfWeek(TEAM_ID, "TUE"))
                    .willReturn(Optional.empty());
            given(businessHourRepository.save(any(ReservationBusinessHourEntity.class))).willReturn(newEntity);
            given(reservationMapper.toBusinessHourResponseList(any())).willReturn(List.of(response));

            // When
            BusinessHoursUpdateOutcome result = service.updateBusinessHours(TEAM_ID, request);

            // Then: 新規行は変更扱い（生成対象）。TUE が changedDays に含まれる。
            assertThat(result.hours()).hasSize(1);
            assertThat(result.changedDays()).containsExactly(ReservationDayOfWeek.TUE);
            verify(businessHourRepository).save(any(ReservationBusinessHourEntity.class));
        }

        @Test
        @DisplayName("異常系: 開始時刻が終了時刻以降の場合INVALID_TIME_RANGEエラー")
        void 営業時間更新_時刻逆転() {
            // Given
            BusinessHourEntry entry = new BusinessHourEntry(
                    "MON", true, LocalTime.of(18, 0), LocalTime.of(9, 0));
            BusinessHoursUpdateRequest request = new BusinessHoursUpdateRequest(List.of(entry));

            // When / Then
            assertThatThrownBy(() -> service.updateBusinessHours(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("正常系: 休業日(isOpen=false)の場合は時刻バリデーションをスキップする")
        void 営業時間更新_休業日() {
            // Given
            BusinessHourEntry entry = new BusinessHourEntry("SUN", false, null, null);
            BusinessHoursUpdateRequest request = new BusinessHoursUpdateRequest(List.of(entry));
            ReservationBusinessHourEntity newEntity = ReservationBusinessHourEntity.builder()
                    .teamId(TEAM_ID)
                    .dayOfWeek("SUN")
                    .isOpen(false)
                    .build();
            BusinessHourResponse response = BusinessHourResponse.builder()
                    .id(3L)
                    .teamId(TEAM_ID)
                    .businessStatus(new BusinessHourResponse.BusinessStatusDto("SUN", false, null, null))
                    .build();

            given(businessHourRepository.findByTeamIdAndDayOfWeek(TEAM_ID, "SUN"))
                    .willReturn(Optional.empty());
            given(businessHourRepository.save(any(ReservationBusinessHourEntity.class))).willReturn(newEntity);
            given(reservationMapper.toBusinessHourResponseList(any())).willReturn(List.of(response));

            // When
            BusinessHoursUpdateOutcome result = service.updateBusinessHours(TEAM_ID, request);

            // Then
            assertThat(result.hours()).hasSize(1);
            assertThat(result.hours().get(0).getBusinessStatus().isOpen()).isFalse();
        }

        @Test
        @DisplayName("S-6②: 変更のなかった曜日は changedDays に含めない（差分生成 scope の観測点）")
        void 営業時間更新_無変更曜日は差分に含めない() {
            // Given: 現行 MON 10:00-19:00 と<b>完全一致</b>する PUT（isOpen/open/close すべて不変）
            ReservationBusinessHourEntity existing = ReservationBusinessHourEntity.builder()
                    .teamId(TEAM_ID).dayOfWeek("MON").isOpen(true)
                    .openTime(LocalTime.of(10, 0)).closeTime(LocalTime.of(19, 0)).build();
            BusinessHourEntry entry = new BusinessHourEntry(
                    "MON", true, LocalTime.of(10, 0), LocalTime.of(19, 0));
            BusinessHoursUpdateRequest request = new BusinessHoursUpdateRequest(List.of(entry));
            given(businessHourRepository.findByTeamIdAndDayOfWeek(TEAM_ID, "MON"))
                    .willReturn(Optional.of(existing));
            given(businessHourRepository.save(existing)).willReturn(existing);
            given(reservationMapper.toBusinessHourResponseList(any()))
                    .willReturn(List.of(createBusinessHourResponse()));

            // When
            BusinessHoursUpdateOutcome result = service.updateBusinessHours(TEAM_ID, request);

            // Then: 差分なし → changedDays 空（当該曜日の生成は走らない）
            assertThat(result.changedDays()).isEmpty();
        }

        @Test
        @DisplayName("S-6②: 時間帯を変更した曜日のみ changedDays に入る（拡大の自動追い付き対象）")
        void 営業時間更新_変更曜日のみ差分に入る() {
            // Given: 現行 MON 10:00-18:00 → PUT で 9:00-18:00 に拡大（open_time 変更）
            ReservationBusinessHourEntity existing = ReservationBusinessHourEntity.builder()
                    .teamId(TEAM_ID).dayOfWeek("MON").isOpen(true)
                    .openTime(LocalTime.of(10, 0)).closeTime(LocalTime.of(18, 0)).build();
            BusinessHourEntry entry = new BusinessHourEntry(
                    "MON", true, LocalTime.of(9, 0), LocalTime.of(18, 0));
            BusinessHoursUpdateRequest request = new BusinessHoursUpdateRequest(List.of(entry));
            given(businessHourRepository.findByTeamIdAndDayOfWeek(TEAM_ID, "MON"))
                    .willReturn(Optional.of(existing));
            given(businessHourRepository.save(existing)).willReturn(existing);
            given(reservationMapper.toBusinessHourResponseList(any()))
                    .willReturn(List.of(createBusinessHourResponse()));

            // When
            BusinessHoursUpdateOutcome result = service.updateBusinessHours(TEAM_ID, request);

            // Then
            assertThat(result.changedDays()).containsExactly(ReservationDayOfWeek.MON);
        }
    }

    // ========================================
    // getBlockedTimes
    // ========================================

    @Nested
    @DisplayName("getBlockedTimes")
    class GetBlockedTimes {

        @Test
        @DisplayName("正常系: 特定日のブロック時間が返却される")
        void ブロック時間取得_正常() {
            // Given
            LocalDate date = LocalDate.of(2026, 4, 1);
            List<ReservationBlockedTimeEntity> entities = List.of(createBlockedTimeEntity());
            List<BlockedTimeResponse> responses = List.of(createBlockedTimeResponse());
            given(blockedTimeRepository.findEffectiveOnDate(TEAM_ID, date, date.minusDays(1)))
                    .willReturn(entities);
            // 機能B: 一覧は resourceName 一括解決のため singular mapper を entity ごとに呼ぶ。
            given(reservationMapper.toBlockedTimeResponse(any(ReservationBlockedTimeEntity.class)))
                    .willReturn(responses.get(0));

            // When
            List<BlockedTimeResponse> result = service.getBlockedTimes(TEAM_ID, date);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAudit().reason()).isEqualTo("昼休み");
        }
    }

    // ========================================
    // listBlockedTimes
    // ========================================

    @Nested
    @DisplayName("listBlockedTimes")
    class ListBlockedTimes {

        @Test
        @DisplayName("正常系: 日付範囲でブロック時間一覧が返却される")
        void ブロック時間一覧_正常() {
            // Given
            LocalDate from = LocalDate.of(2026, 4, 1);
            LocalDate to = LocalDate.of(2026, 4, 7);
            List<ReservationBlockedTimeEntity> entities = List.of(createBlockedTimeEntity());
            List<BlockedTimeResponse> responses = List.of(createBlockedTimeResponse());
            given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                    TEAM_ID, from, to)).willReturn(entities);
            // 機能B: 一覧は resourceName 一括解決のため singular mapper を entity ごとに呼ぶ。
            given(reservationMapper.toBlockedTimeResponse(any(ReservationBlockedTimeEntity.class)))
                    .willReturn(responses.get(0));

            // When
            List<BlockedTimeResponse> result = service.listBlockedTimes(TEAM_ID, USER_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createBlockedTime
    // ========================================

    @Nested
    @DisplayName("createBlockedTime")
    class CreateBlockedTime {

        @Test
        @DisplayName("正常系: ブロック時間が作成される")
        void ブロック時間作成_正常() {
            // Given
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), LocalTime.of(12, 0), LocalTime.of(13, 0), "昼休み", null, null);
            ReservationBlockedTimeEntity savedEntity = createBlockedTimeEntity();
            BlockedTimeResponse response = createBlockedTimeResponse();

            given(blockedTimeRepository.save(any(ReservationBlockedTimeEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toBlockedTimeResponse(savedEntity)).willReturn(response);

            // When
            BlockedTimeResponse result = service.createBlockedTime(TEAM_ID, request, CREATED_BY);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getAudit().reason()).isEqualTo("昼休み");
            verify(blockedTimeRepository).save(any(ReservationBlockedTimeEntity.class));
        }

        @Test
        @DisplayName("異常系: 開始時刻が終了時刻以降の場合INVALID_TIME_RANGEエラー")
        void ブロック時間作成_時刻逆転() {
            // Given
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), LocalTime.of(15, 0), LocalTime.of(12, 0), "理由", null, null);

            // When / Then
            assertThatThrownBy(() -> service.createBlockedTime(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("正常系: 時刻がnullの場合(終日ブロック)は時刻バリデーションをスキップする")
        void ブロック時間作成_終日() {
            // Given
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), null, null, "終日休業", null, null);
            ReservationBlockedTimeEntity savedEntity = createBlockedTimeEntity();
            BlockedTimeResponse response = createBlockedTimeResponse();

            given(blockedTimeRepository.save(any(ReservationBlockedTimeEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toBlockedTimeResponse(savedEntity)).willReturn(response);

            // When
            BlockedTimeResponse result = service.createBlockedTime(TEAM_ID, request, CREATED_BY);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("日跨ぎblockは23:00→01:00を保存しresponseにも返す")
        void overnightBlockedTimeCreate() {
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), LocalTime.of(23, 0), LocalTime.of(1, 0),
                    "overnight", null, null, true);
            ReservationBlockedTimeEntity saved = ReservationBlockedTimeEntity.builder()
                    .teamId(TEAM_ID).blockedDate(request.getBlockedDate()).startTime(request.getStartTime())
                    .endTime(request.getEndTime()).endsNextDay(true).build();
            BlockedTimeResponse response = BlockedTimeResponse.builder().endsNextDay(true).build();
            given(blockedTimeRepository.save(any())).willReturn(saved);
            given(reservationMapper.toBlockedTimeResponse(saved)).willReturn(response);

            assertThat(service.createBlockedTime(TEAM_ID, request, CREATED_BY).getEndsNextDay()).isTrue();
            assertThat(saved.getEndsNextDay()).isTrue();
        }
    }

    // ========================================
    // updateBlockedTime
    // ========================================

    @Nested
    @DisplayName("updateBlockedTime")
    class UpdateBlockedTime {

        @Test
        @DisplayName("正常系: ブロック時間が更新される")
        void ブロック時間更新_正常() {
            // Given
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 3), LocalTime.of(14, 0), LocalTime.of(15, 0), "休憩", null, null);
            ReservationBlockedTimeEntity entity = createBlockedTimeEntity();
            BlockedTimeResponse response = createBlockedTimeResponse();

            given(blockedTimeRepository.findByIdAndTeamId(BLOCKED_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(blockedTimeRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toBlockedTimeResponse(entity)).willReturn(response);

            // When
            BlockedTimeResponse result = service.updateBlockedTime(TEAM_ID, BLOCKED_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(blockedTimeRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: ブロック時間が存在しない場合BLOCKED_TIME_NOT_FOUNDエラー")
        void ブロック時間更新_存在しない() {
            // Given
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 3), null, null, "理由", null, null);
            given(blockedTimeRepository.findByIdAndTeamId(BLOCKED_ID, TEAM_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateBlockedTime(TEAM_ID, BLOCKED_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.BLOCKED_TIME_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 更新時に時刻が逆転している場合INVALID_TIME_RANGEエラー")
        void ブロック時間更新_時刻逆転() {
            // Given
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 3), LocalTime.of(16, 0), LocalTime.of(14, 0), "理由", null, null);
            ReservationBlockedTimeEntity entity = createBlockedTimeEntity();
            given(blockedTimeRepository.findByIdAndTeamId(BLOCKED_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.updateBlockedTime(TEAM_ID, BLOCKED_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("PATCH日跨ぎblockはendsNextDayを更新して返す")
        void overnightBlockedTimeUpdate() {
            ReservationBlockedTimeEntity entity = createBlockedTimeEntity();
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), LocalTime.of(23, 0), LocalTime.of(1, 0),
                    "overnight", null, null, true);
            given(blockedTimeRepository.findByIdAndTeamId(BLOCKED_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(blockedTimeRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toBlockedTimeResponse(entity))
                    .willReturn(BlockedTimeResponse.builder().endsNextDay(true).build());

            assertThat(service.updateBlockedTime(TEAM_ID, BLOCKED_ID, request).getEndsNextDay()).isTrue();
            assertThat(entity.getEndsNextDay()).isTrue();
        }
    }

    // deleteBlockedTime
    // ========================================

    @Nested
    @DisplayName("deleteBlockedTime")
    class DeleteBlockedTime {

        @Test
        @DisplayName("正常系: ブロック時間が削除される")
        void ブロック時間削除_正常() {
            // Given
            ReservationBlockedTimeEntity entity = createBlockedTimeEntity();
            given(blockedTimeRepository.findByIdAndTeamId(BLOCKED_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When
            service.deleteBlockedTime(TEAM_ID, BLOCKED_ID);

            // Then
            verify(blockedTimeRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: ブロック時間が存在しない場合BLOCKED_TIME_NOT_FOUNDエラー")
        void ブロック時間削除_存在しない() {
            // Given
            given(blockedTimeRepository.findByIdAndTeamId(BLOCKED_ID, TEAM_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteBlockedTime(TEAM_ID, BLOCKED_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.BLOCKED_TIME_NOT_FOUND);
        }
    }

    // ========================================
    // hasBusinessHours
    // ========================================

    @Nested
    @DisplayName("hasBusinessHours")
    class HasBusinessHours {

        @Test
        @DisplayName("正常系: 営業時間設定が存在する場合trueを返す")
        void 営業時間存在確認_あり() {
            // Given
            given(businessHourRepository.existsByTeamId(TEAM_ID)).willReturn(true);

            // When
            boolean result = service.hasBusinessHours(TEAM_ID);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: 営業時間設定が存在しない場合falseを返す")
        void 営業時間存在確認_なし() {
            // Given
            given(businessHourRepository.existsByTeamId(TEAM_ID)).willReturn(false);

            // When
            boolean result = service.hasBusinessHours(TEAM_ID);

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // 機能B: 予約不可枠の 409 ガード・impact・軸バリデーション（受け入れ条件 B-6/B-7/B-9）
    // ========================================

    @Nested
    @DisplayName("機能B 予約不可枠 409ガード / impact / 軸")
    class Unavailability {

        private static final List<ReservationStatus> ACTIVE =
                List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

        private ReservationEntity reservation(Long id, Long slotId, Long userId, ReservationStatus status) {
            ReservationEntity e = ReservationEntity.builder()
                    .reservationSlotId(slotId).lineId(1L).teamId(TEAM_ID).userId(userId).build();
            org.springframework.test.util.ReflectionTestUtils.setField(e, "id", id);
            org.springframework.test.util.ReflectionTestUtils.setField(e, "status", status);
            return e;
        }

        @Test
        @DisplayName("B-6: overlap する active 予約が存在すると RESERVATION_027（409）で拒否し blocked_times に書き込まない")
        void B6_409ガード() {
            // Given: 全日 TEAM 枠 → 時刻条件なしの findActiveReservationsOnDate で active 予約 1 件ヒット。
            // （全日は LocalTime.MAX を使わない根治後の経路。実DBの TIME 型丸め問題を排除・2026-07-10）
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), null, null, "臨時休業", null, null);
            given(reservationRepository.findActiveReservationsOnDate(
                    org.mockito.ArgumentMatchers.eq(TEAM_ID),
                    org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 4, 2)),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(ACTIVE)))
                    .willReturn(List.of(reservation(10L, 1L, 789L, ReservationStatus.CONFIRMED)));

            // When / Then
            assertThatThrownBy(() -> service.createBlockedTime(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);
            // 副作用ゼロ: blocked_times に保存しない。
            verify(blockedTimeRepository, org.mockito.Mockito.never()).save(any(ReservationBlockedTimeEntity.class));
        }

        @Test
        @DisplayName("B-6: overlap 0 件（終端状態のみ）なら登録される（CANCELLED/COMPLETED/NO_SHOW は observ 対象外）")
        void B6_終端状態は登録可() {
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), null, null, "臨時休業", null, null);
            // 全日ブロックは時刻条件なしの findActiveReservationsOnDate を通る（根治後の経路）。
            // ACTIVE(PENDING/CONFIRMED) のクエリは 0 件（終端状態は observ 点である本クエリに含まれない）。
            given(reservationRepository.findActiveReservationsOnDate(
                    any(), any(), org.mockito.ArgumentMatchers.isNull(), any()))
                    .willReturn(List.of());
            ReservationBlockedTimeEntity saved = createBlockedTimeEntity();
            given(blockedTimeRepository.save(any(ReservationBlockedTimeEntity.class))).willReturn(saved);
            given(reservationMapper.toBlockedTimeResponse(saved)).willReturn(createBlockedTimeResponse());

            BlockedTimeResponse result = service.createBlockedTime(TEAM_ID, request, CREATED_BY);

            assertThat(result).isNotNull();
            verify(blockedTimeRepository).save(any(ReservationBlockedTimeEntity.class));
        }

        @Test
        @DisplayName("B-7: impact は overlap する active 予約の件数＋一覧（氏名込み）を返し副作用ゼロ")
        void B7_impact() {
            ReservationEntity r1 = reservation(10L, 100L, 789L, ReservationStatus.CONFIRMED);
            ReservationEntity r2 = reservation(11L, 101L, 790L, ReservationStatus.PENDING);
            given(reservationRepository.findActiveReservationsOverlappingUnavailability(
                    org.mockito.ArgumentMatchers.eq(TEAM_ID),
                    org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 4, 2)),
                    org.mockito.ArgumentMatchers.eq(50L),
                    org.mockito.ArgumentMatchers.eq(LocalTime.of(10, 0)),
                    org.mockito.ArgumentMatchers.eq(LocalTime.of(11, 0)),
                    org.mockito.ArgumentMatchers.eq(ACTIVE)))
                    .willReturn(List.of(r1, r2));
            given(slotRepository.findAllById(any())).willReturn(List.of(
                    slotEntity(100L, 50L), slotEntity(101L, 50L)));
            given(nameResolverService.resolveUserFullNames(any()))
                    .willReturn(java.util.Map.of(789L, "山田太郎", 790L, "鈴木花子", 50L, "田中スタッフ"));

            var result = service.getBlockedTimeImpact(TEAM_ID, LocalDate.of(2026, 4, 2),
                    com.mannschaft.app.reservation.ReservationBlockedResourceType.STAFF, 50L,
                    LocalTime.of(10, 0), LocalTime.of(11, 0));

            assertThat(result.getAffectedCount()).isEqualTo(2);
            assertThat(result.getReservations()).extracting(
                    com.mannschaft.app.reservation.dto.BlockedTimeImpactResponse.ImpactedReservationDto::userName)
                    .containsExactlyInAnyOrder("山田太郎", "鈴木花子");
            // 副作用ゼロ: blocked_times を触らない。
            verify(blockedTimeRepository, org.mockito.Mockito.never()).save(any(ReservationBlockedTimeEntity.class));
        }

        @Test
        @DisplayName("日跨ぎimpactは翌日開始予約も候補に含める")
        void impactOvernightIncludesNextDateCandidates() {
            LocalDate date = LocalDate.of(2026, 4, 2);
            given(reservationRepository.findActiveReservationsOnDates(
                    eq(TEAM_ID), eq(List.of(date.minusDays(1), date, date.plusDays(1))),
                    eq(null), eq(ACTIVE))).willReturn(List.of());

            service.getBlockedTimeImpact(TEAM_ID, date,
                    com.mannschaft.app.reservation.ReservationBlockedResourceType.TEAM, null,
                    LocalTime.of(23, 0), LocalTime.of(1, 0), true);

            verify(reservationRepository).findActiveReservationsOnDates(
                    eq(TEAM_ID), eq(List.of(date.minusDays(1), date, date.plusDays(1))),
                    eq(null), eq(ACTIVE));
        }

        @Test
        @DisplayName("null/nullの日跨ぎblockは全日化せず400で拒否する")
        void nullTimesWithEndsNextDayAreRejected() {
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), null, null, "終日", null, null, true);

            assertThatThrownBy(() -> service.createBlockedTime(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
            verify(blockedTimeRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("B-9: resourceType=STAFF かつ resourceId 未指定は 400（COMMON_001）")
        void B9_STAFF未指定は400() {
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), null, null, "スタッフ研修",
                    com.mannschaft.app.reservation.ReservationBlockedResourceType.STAFF, null);

            assertThatThrownBy(() -> service.createBlockedTime(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_001);
        }

        private ReservationSlotEntity slotEntity(Long id, Long staffUserId) {
            ReservationSlotEntity s = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID).staffUserId(staffUserId).slotDate(LocalDate.of(2026, 4, 2))
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0)).build();
            org.springframework.test.util.ReflectionTestUtils.setField(s, "id", id);
            return s;
        }
    }
}
