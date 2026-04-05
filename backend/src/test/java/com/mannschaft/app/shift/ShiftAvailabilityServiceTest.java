package com.mannschaft.app.shift;

import com.mannschaft.app.shift.dto.AvailabilityDefaultRequest;
import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.BulkAvailabilityDefaultRequest;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.repository.MemberAvailabilityDefaultRepository;
import com.mannschaft.app.shift.service.ShiftAvailabilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftAvailabilityService} の単体テスト。
 * デフォルト勤務可能時間の取得・設定・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftAvailabilityService 単体テスト")
class ShiftAvailabilityServiceTest {

    @Mock
    private MemberAvailabilityDefaultRepository availabilityRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @InjectMocks
    private ShiftAvailabilityService shiftAvailabilityService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 10L;
    private static final Long TEAM_ID = 1L;

    private MemberAvailabilityDefaultEntity createAvailabilityEntity() {
        return MemberAvailabilityDefaultEntity.builder()
                .userId(USER_ID)
                .teamId(TEAM_ID)
                .dayOfWeek(1)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .preference(ShiftPreference.AVAILABLE)
                .note("月曜日")
                .build();
    }

    private AvailabilityDefaultResponse createAvailabilityResponse() {
        return new AvailabilityDefaultResponse(
                1L, USER_ID, TEAM_ID, 1,
                LocalTime.of(9, 0), LocalTime.of(17, 0),
                "AVAILABLE", "月曜日");
    }

    // ========================================
    // getAvailabilityDefaults
    // ========================================

    @Nested
    @DisplayName("getAvailabilityDefaults")
    class GetAvailabilityDefaults {

        @Test
        @DisplayName("デフォルト勤務可能時間取得_正常_リスト返却")
        void デフォルト勤務可能時間取得_正常_リスト返却() {
            // Given
            MemberAvailabilityDefaultEntity entity = createAvailabilityEntity();
            AvailabilityDefaultResponse response = createAvailabilityResponse();
            given(availabilityRepository.findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(USER_ID, TEAM_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toAvailabilityResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<AvailabilityDefaultResponse> result = shiftAvailabilityService.getAvailabilityDefaults(USER_ID, TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDayOfWeek()).isEqualTo(1);
        }

        @Test
        @DisplayName("デフォルト勤務可能時間取得_データなし_空リスト返却")
        void デフォルト勤務可能時間取得_データなし_空リスト返却() {
            // Given
            given(availabilityRepository.findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(USER_ID, TEAM_ID))
                    .willReturn(List.of());
            given(shiftMapper.toAvailabilityResponseList(List.of()))
                    .willReturn(List.of());

            // When
            List<AvailabilityDefaultResponse> result = shiftAvailabilityService.getAvailabilityDefaults(USER_ID, TEAM_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // setAvailabilityDefaults
    // ========================================

    @Nested
    @DisplayName("setAvailabilityDefaults")
    class SetAvailabilityDefaults {

        @Test
        @DisplayName("デフォルト勤務可能時間設定_正常_削除後に再作成")
        void デフォルト勤務可能時間設定_正常_削除後に再作成() {
            // Given
            AvailabilityDefaultRequest availReq = new AvailabilityDefaultRequest(
                    1, LocalTime.of(9, 0), LocalTime.of(17, 0), "PREFERRED", "月曜希望");
            BulkAvailabilityDefaultRequest req = new BulkAvailabilityDefaultRequest(List.of(availReq));
            MemberAvailabilityDefaultEntity savedEntity = createAvailabilityEntity();
            AvailabilityDefaultResponse response = createAvailabilityResponse();

            given(availabilityRepository.saveAll(anyList())).willReturn(List.of(savedEntity));
            given(shiftMapper.toAvailabilityResponseList(List.of(savedEntity)))
                    .willReturn(List.of(response));

            // When
            List<AvailabilityDefaultResponse> result = shiftAvailabilityService.setAvailabilityDefaults(USER_ID, TEAM_ID, req);

            // Then
            assertThat(result).hasSize(1);
            verify(availabilityRepository).deleteByUserIdAndTeamId(USER_ID, TEAM_ID);
            verify(availabilityRepository).saveAll(anyList());
        }
    }

    // ========================================
    // deleteAvailabilityDefaults
    // ========================================

    @Nested
    @DisplayName("deleteAvailabilityDefaults")
    class DeleteAvailabilityDefaults {

        @Test
        @DisplayName("デフォルト勤務可能時間削除_正常_削除が呼ばれる")
        void デフォルト勤務可能時間削除_正常_削除が呼ばれる() {
            // When
            shiftAvailabilityService.deleteAvailabilityDefaults(USER_ID, TEAM_ID);

            // Then
            verify(availabilityRepository).deleteByUserIdAndTeamId(USER_ID, TEAM_ID);
        }
    }
}
