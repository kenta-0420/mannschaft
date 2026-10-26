package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.FacilityType;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FacilityAccessGuard} の単体テスト（認可根治 Wave5 早馬）。
 * entity 由来 scope 解決・BOLA 404 存在秘匿・read/write/owner の粒度を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityAccessGuard 単体テスト")
class FacilityAccessGuardTest {

    @Mock
    private SharedFacilityRepository facilityRepository;

    @Mock
    private FacilityBookingRepository bookingRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private FacilityAccessGuard accessGuard;

    private static final Long USER_ID = 100L;
    private static final Long FACILITY_ID = 5L;
    private static final Long BOOKING_ID = 10L;
    private static final Long OWNER_ID = 42L;

    private SharedFacilityEntity facility(String scopeType, Long scopeId) {
        return SharedFacilityEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).name("会議室")
                .facilityType(FacilityType.MEETING_ROOM).capacity(10).createdBy(1L).build();
    }

    private FacilityBookingEntity booking(Long facilityId, Long bookedBy) {
        return FacilityBookingEntity.builder()
                .facilityId(facilityId).bookedBy(bookedBy)
                .bookingDate(LocalDate.now().plusDays(1))
                .timeFrom(LocalTime.of(10, 0)).timeTo(LocalTime.of(12, 0)).slotCount(4)
                .build();
    }

    @Nested
    @DisplayName("requireFacilityMember（施設 read）")
    class FacilityMember {

        @Test
        @DisplayName("scope 一致: entity 由来 scope で checkMembership を呼ぶ")
        void 一致_checkMembershipが呼ばれる() {
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, "TEAM", 1L))
                    .willReturn(Optional.of(facility("TEAM", 1L)));

            accessGuard.requireFacilityMember("TEAM", 1L, FACILITY_ID, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, 1L, "TEAM");
        }

        @Test
        @DisplayName("越境（別 scope の facilityId）は FACILITY_001（404 存在秘匿）")
        void 越境_FACILITY001() {
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, "TEAM", 1L))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> accessGuard.requireFacilityMember("TEAM", 1L, FACILITY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_001"));
        }
    }

    @Nested
    @DisplayName("requireFacilityAdmin（施設 write）")
    class FacilityAdmin {

        @Test
        @DisplayName("scope 一致: entity 由来 scope で checkAdminOrAbove を呼ぶ")
        void 一致_checkAdminOrAboveが呼ばれる() {
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, "ORGANIZATION", 2L))
                    .willReturn(Optional.of(facility("ORGANIZATION", 2L)));

            accessGuard.requireFacilityAdmin("ORGANIZATION", 2L, FACILITY_ID, USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, 2L, "ORGANIZATION");
        }
    }

    @Nested
    @DisplayName("requireBookingMember / requireBookingAdmin（予約 read/write）")
    class BookingScope {

        @Test
        @DisplayName("booking→facility の scope 一致: entity 由来 scope で checkMembership")
        void 一致_checkMembership() {
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking(FACILITY_ID, OWNER_ID)));
            given(facilityRepository.findById(FACILITY_ID)).willReturn(Optional.of(facility("TEAM", 1L)));

            accessGuard.requireBookingMember("TEAM", 1L, BOOKING_ID, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, 1L, "TEAM");
        }

        @Test
        @DisplayName("越境（booking の facility が別 scope）は FACILITY_006（404 存在秘匿）")
        void 越境_FACILITY006() {
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking(FACILITY_ID, OWNER_ID)));
            // booking の facility は teamB(2) 所属だが、パスは teamA(1)
            given(facilityRepository.findById(FACILITY_ID)).willReturn(Optional.of(facility("TEAM", 2L)));

            assertThatThrownBy(() -> accessGuard.requireBookingAdmin("TEAM", 1L, BOOKING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_006"));
        }

        @Test
        @DisplayName("予約が存在しないは FACILITY_006")
        void 予約なし_FACILITY006() {
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accessGuard.requireBookingMember("TEAM", 1L, BOOKING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_006"));
        }
    }

    @Nested
    @DisplayName("requireBookingOwnerOrAdmin（予約 更新/取消: 本人 or ADMIN）")
    class BookingOwnerOrAdmin {

        @Test
        @DisplayName("scope 一致: 予約者 bookedBy を渡して checkOwnerOrAdmin を呼ぶ")
        void 一致_checkOwnerOrAdmin() {
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking(FACILITY_ID, OWNER_ID)));
            given(facilityRepository.findById(FACILITY_ID)).willReturn(Optional.of(facility("TEAM", 1L)));

            accessGuard.requireBookingOwnerOrAdmin("TEAM", 1L, BOOKING_ID, USER_ID);

            verify(accessControlService).checkOwnerOrAdmin(USER_ID, OWNER_ID, 1L, "TEAM");
        }

        @Test
        @DisplayName("越境は checkOwnerOrAdmin を呼ばず FACILITY_006")
        void 越境_FACILITY006() {
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking(FACILITY_ID, OWNER_ID)));
            given(facilityRepository.findById(FACILITY_ID)).willReturn(Optional.of(facility("TEAM", 2L)));

            assertThatThrownBy(() -> accessGuard.requireBookingOwnerOrAdmin("TEAM", 1L, BOOKING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_006"));
        }
    }
}
