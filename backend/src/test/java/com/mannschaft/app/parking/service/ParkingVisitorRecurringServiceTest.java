package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.RecurrenceType;
import com.mannschaft.app.parking.dto.CreateVisitorRecurringRequest;
import com.mannschaft.app.parking.dto.UpdateVisitorRecurringRequest;
import com.mannschaft.app.parking.dto.VisitorRecurringResponse;
import com.mannschaft.app.parking.entity.ParkingVisitorRecurringEntity;
import com.mannschaft.app.parking.repository.ParkingVisitorRecurringRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ParkingVisitorRecurringService} の単体テスト。
 * 定期来場者予約テンプレートのCRUD・無効化ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingVisitorRecurringService 単体テスト")
class ParkingVisitorRecurringServiceTest {

    @Mock
    private ParkingVisitorRecurringRepository recurringRepository;

    @Mock
    private ParkingMapper parkingMapper;

    @InjectMocks
    private ParkingVisitorRecurringService parkingVisitorRecurringService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 100L;
    private static final Long SPACE_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long RECURRING_ID = 20L;
    private static final String SCOPE_TYPE = "TEAM";

    private ParkingVisitorRecurringEntity createActiveRecurring() {
        return ParkingVisitorRecurringEntity.builder()
                .userId(USER_ID)
                .spaceId(SPACE_ID)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .recurrenceType(RecurrenceType.WEEKLY)
                .dayOfWeek(1)
                .timeFrom(LocalTime.of(9, 0))
                .timeTo(LocalTime.of(17, 0))
                .visitorName("田中太郎")
                .visitorPlateNumber("品川300あ1234")
                .purpose("定期訪問")
                .nextGenerateDate(LocalDate.of(2026, 4, 1))
                .build();
    }

    // ========================================
    // list
    // ========================================

    @Nested
    @DisplayName("list")
    class List_ {

        @Test
        @DisplayName("正常系: テンプレート一覧が取得できる")
        void list_正常_一覧取得() {
            // Given
            ParkingVisitorRecurringEntity entity = createActiveRecurring();
            given(recurringRepository.findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of(entity));
            given(parkingMapper.toVisitorRecurringResponseList(anyList())).willReturn(List.of());

            // When
            List<VisitorRecurringResponse> result = parkingVisitorRecurringService.list(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isNotNull();
            verify(recurringRepository).findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("正常系: テンプレートが存在しない場合空リストが返る")
        void list_テンプレートなし_空リスト() {
            // Given
            given(recurringRepository.findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of());
            given(parkingMapper.toVisitorRecurringResponseList(anyList())).willReturn(List.of());

            // When
            List<VisitorRecurringResponse> result = parkingVisitorRecurringService.list(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // create
    // ========================================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: テンプレートが作成される")
        void create_正常_テンプレートが作成される() {
            // Given
            CreateVisitorRecurringRequest request = new CreateVisitorRecurringRequest(
                    SPACE_ID, "WEEKLY", 1, null,
                    LocalTime.of(9, 0), LocalTime.of(17, 0),
                    "田中太郎", "品川300あ1234", "定期訪問",
                    LocalDate.of(2026, 4, 1));
            given(recurringRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorRecurringResponse(any())).willReturn(null);

            // When
            parkingVisitorRecurringService.create(USER_ID, SCOPE_TYPE, SCOPE_ID, request);

            // Then
            verify(recurringRepository).save(any(ParkingVisitorRecurringEntity.class));
        }

        @Test
        @DisplayName("正常系: MONTHLYタイプでdayOfMonthが設定される")
        void create_MONTHLY_dayOfMonth設定() {
            // Given
            CreateVisitorRecurringRequest request = new CreateVisitorRecurringRequest(
                    SPACE_ID, "MONTHLY", null, 15,
                    LocalTime.of(10, 0), LocalTime.of(12, 0),
                    "佐藤花子", null, "月次訪問",
                    LocalDate.of(2026, 4, 15));
            given(recurringRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorRecurringResponse(any())).willReturn(null);

            // When
            parkingVisitorRecurringService.create(USER_ID, SCOPE_TYPE, SCOPE_ID, request);

            // Then
            verify(recurringRepository).save(any(ParkingVisitorRecurringEntity.class));
        }
    }

    // ========================================
    // update
    // ========================================

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: テンプレートが更新される")
        void update_正常_更新される() {
            // Given
            ParkingVisitorRecurringEntity entity = createActiveRecurring();
            UpdateVisitorRecurringRequest request = new UpdateVisitorRecurringRequest(
                    "BIWEEKLY", 3, null,
                    LocalTime.of(10, 0), LocalTime.of(16, 0),
                    "更新名", "更新ナンバー", "更新目的");
            given(recurringRepository.findByIdAndUserIdAndScopeTypeAndScopeId(RECURRING_ID, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(recurringRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorRecurringResponse(any())).willReturn(null);

            // When
            parkingVisitorRecurringService.update(USER_ID, SCOPE_TYPE, SCOPE_ID, RECURRING_ID, request);

            // Then
            assertThat(entity.getRecurrenceType()).isEqualTo(RecurrenceType.BIWEEKLY);
            assertThat(entity.getVisitorName()).isEqualTo("更新名");
            assertThat(entity.getDayOfWeek()).isEqualTo(3);
            verify(recurringRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: テンプレートが見つからない場合PARKING_024例外")
        void update_不在_PARKING024例外() {
            // Given
            UpdateVisitorRecurringRequest request = new UpdateVisitorRecurringRequest(
                    "WEEKLY", 1, null,
                    LocalTime.of(9, 0), LocalTime.of(17, 0),
                    "名前", null, null);
            given(recurringRepository.findByIdAndUserIdAndScopeTypeAndScopeId(RECURRING_ID, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingVisitorRecurringService.update(USER_ID, SCOPE_TYPE, SCOPE_ID, RECURRING_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_024"));
        }
    }

    // ========================================
    // delete
    // ========================================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: テンプレートが無効化される")
        void delete_正常_無効化される() {
            // Given
            ParkingVisitorRecurringEntity entity = createActiveRecurring();
            given(recurringRepository.findByIdAndUserIdAndScopeTypeAndScopeId(RECURRING_ID, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            parkingVisitorRecurringService.delete(USER_ID, SCOPE_TYPE, SCOPE_ID, RECURRING_ID);

            // Then
            assertThat(entity.getIsActive()).isFalse();
            verify(recurringRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: テンプレートが見つからない場合PARKING_024例外")
        void delete_不在_PARKING024例外() {
            // Given
            given(recurringRepository.findByIdAndUserIdAndScopeTypeAndScopeId(RECURRING_ID, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingVisitorRecurringService.delete(USER_ID, SCOPE_TYPE, SCOPE_ID, RECURRING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_024"));
        }
    }
}
