package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourEntry;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
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

    @InjectMocks
    private ReservationBusinessHourService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
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
        return new BusinessHourResponse(
                1L, TEAM_ID, "MON", true, LocalTime.of(9, 0), LocalTime.of(18, 0));
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
        return new BlockedTimeResponse(
                BLOCKED_ID, TEAM_ID, LocalDate.of(2026, 4, 1),
                LocalTime.of(12, 0), LocalTime.of(13, 0),
                "昼休み", CREATED_BY, null, null);
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
            List<BusinessHourResponse> result = service.getBusinessHours(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDayOfWeek()).isEqualTo("MON");
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
            List<BusinessHourResponse> result = service.updateBusinessHours(TEAM_ID, request);

            // Then
            assertThat(result).hasSize(1);
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
            BusinessHourResponse response = new BusinessHourResponse(
                    2L, TEAM_ID, "TUE", true, LocalTime.of(9, 0), LocalTime.of(17, 0));

            given(businessHourRepository.findByTeamIdAndDayOfWeek(TEAM_ID, "TUE"))
                    .willReturn(Optional.empty());
            given(businessHourRepository.save(any(ReservationBusinessHourEntity.class))).willReturn(newEntity);
            given(reservationMapper.toBusinessHourResponseList(any())).willReturn(List.of(response));

            // When
            List<BusinessHourResponse> result = service.updateBusinessHours(TEAM_ID, request);

            // Then
            assertThat(result).hasSize(1);
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
            BusinessHourResponse response = new BusinessHourResponse(
                    3L, TEAM_ID, "SUN", false, null, null);

            given(businessHourRepository.findByTeamIdAndDayOfWeek(TEAM_ID, "SUN"))
                    .willReturn(Optional.empty());
            given(businessHourRepository.save(any(ReservationBusinessHourEntity.class))).willReturn(newEntity);
            given(reservationMapper.toBusinessHourResponseList(any())).willReturn(List.of(response));

            // When
            List<BusinessHourResponse> result = service.updateBusinessHours(TEAM_ID, request);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIsOpen()).isFalse();
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
            given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, date))
                    .willReturn(entities);
            given(reservationMapper.toBlockedTimeResponseList(entities)).willReturn(responses);

            // When
            List<BlockedTimeResponse> result = service.getBlockedTimes(TEAM_ID, date);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getReason()).isEqualTo("昼休み");
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
            given(reservationMapper.toBlockedTimeResponseList(entities)).willReturn(responses);

            // When
            List<BlockedTimeResponse> result = service.listBlockedTimes(TEAM_ID, from, to);

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
                    LocalDate.of(2026, 4, 2), LocalTime.of(12, 0), LocalTime.of(13, 0), "昼休み");
            ReservationBlockedTimeEntity savedEntity = createBlockedTimeEntity();
            BlockedTimeResponse response = createBlockedTimeResponse();

            given(blockedTimeRepository.save(any(ReservationBlockedTimeEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toBlockedTimeResponse(savedEntity)).willReturn(response);

            // When
            BlockedTimeResponse result = service.createBlockedTime(TEAM_ID, request, CREATED_BY);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getReason()).isEqualTo("昼休み");
            verify(blockedTimeRepository).save(any(ReservationBlockedTimeEntity.class));
        }

        @Test
        @DisplayName("異常系: 開始時刻が終了時刻以降の場合INVALID_TIME_RANGEエラー")
        void ブロック時間作成_時刻逆転() {
            // Given
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.of(2026, 4, 2), LocalTime.of(15, 0), LocalTime.of(12, 0), "理由");

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
                    LocalDate.of(2026, 4, 2), null, null, "終日休業");
            ReservationBlockedTimeEntity savedEntity = createBlockedTimeEntity();
            BlockedTimeResponse response = createBlockedTimeResponse();

            given(blockedTimeRepository.save(any(ReservationBlockedTimeEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toBlockedTimeResponse(savedEntity)).willReturn(response);

            // When
            BlockedTimeResponse result = service.createBlockedTime(TEAM_ID, request, CREATED_BY);

            // Then
            assertThat(result).isNotNull();
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
                    LocalDate.of(2026, 4, 3), LocalTime.of(14, 0), LocalTime.of(15, 0), "休憩");
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
                    LocalDate.of(2026, 4, 3), null, null, "理由");
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
                    LocalDate.of(2026, 4, 3), LocalTime.of(16, 0), LocalTime.of(14, 0), "理由");
            ReservationBlockedTimeEntity entity = createBlockedTimeEntity();
            given(blockedTimeRepository.findByIdAndTeamId(BLOCKED_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.updateBlockedTime(TEAM_ID, BLOCKED_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }
    }

    // ========================================
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
}
