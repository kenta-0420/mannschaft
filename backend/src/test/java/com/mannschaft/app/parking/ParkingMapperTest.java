package com.mannschaft.app.parking;

import com.mannschaft.app.parking.dto.*;
import com.mannschaft.app.parking.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ParkingMapperImpl} の単体テスト。MapStruct 生成クラスを直接インスタンス化してテストする。
 */
@DisplayName("ParkingMapper 単体テスト")
class ParkingMapperTest {

    private final ParkingMapperImpl mapper = new ParkingMapperImpl();

    // ===================== Space =====================

    @Nested
    @DisplayName("toSpaceResponse")
    class ToSpaceResponse {

        @Test
        @DisplayName("正常系: 全フィールドが正しく変換される")
        void 区画_全フィールド_変換() throws Exception {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).spaceTypeLabel("屋内")
                    .pricePerMonth(BigDecimal.valueOf(10000))
                    .status(SpaceStatus.VACANT).floor("1F").notes("備考")
                    .applicationStatus(ApplicationStatus.ACCEPTING)
                    .allocationMethod(AllocationMethod.LOTTERY)
                    .applicationDeadline(LocalDateTime.of(2030, 1, 1, 0, 0))
                    .createdBy(100L).build();

            SpaceResponse result = mapper.toSpaceResponse(entity);

            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getScopeId()).isEqualTo(1L);
            assertThat(result.getSpaceNumber()).isEqualTo("A-001");
            assertThat(result.getSpaceType()).isEqualTo("INDOOR");
            assertThat(result.getStatus()).isEqualTo("VACANT");
            assertThat(result.getApplicationStatus()).isEqualTo("ACCEPTING");
            assertThat(result.getAllocationMethod()).isEqualTo("LOTTERY");
            assertThat(result.getPricePerMonth()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        }

        @Test
        @DisplayName("正常系: allocationMethodがnullの場合nullを返す")
        void 区画_allocationMethodNull_変換() {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("ORG").scopeId(2L).spaceNumber("B-001")
                    .spaceType(SpaceType.OUTDOOR)
                    .applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                    .createdBy(100L).build();

            SpaceResponse result = mapper.toSpaceResponse(entity);

            assertThat(result.getAllocationMethod()).isNull();
            assertThat(result.getSpaceType()).isEqualTo("OUTDOOR");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void 区画リスト_変換() {
            ParkingSpaceEntity e1 = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                    .createdBy(100L).build();
            ParkingSpaceEntity e2 = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-002")
                    .spaceType(SpaceType.OUTDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                    .createdBy(100L).build();

            List<SpaceResponse> result = mapper.toSpaceResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getSpaceNumber()).isEqualTo("A-001");
            assertThat(result.get(1).getSpaceNumber()).isEqualTo("A-002");
        }
    }

    // ===================== Vehicle =====================

    @Nested
    @DisplayName("toVehicleResponse")
    class ToVehicleResponse {

        @Test
        @DisplayName("正常系: 車両が変換される")
        void 車両_変換() {
            RegisteredVehicleEntity entity = RegisteredVehicleEntity.builder()
                    .userId(100L).vehicleType(VehicleType.CAR)
                    .plateNumber("encrypted".getBytes())
                    .plateNumberHash("hash").nickname("マイカー").build();

            VehicleResponse result = mapper.toVehicleResponse(entity);

            assertThat(result.getUserId()).isEqualTo(100L);
            assertThat(result.getVehicleType()).isEqualTo("CAR");
            assertThat(result.getNickname()).isEqualTo("マイカー");
            // plateNumberはignore
            assertThat(result.getPlateNumber()).isNull();
        }

        @Test
        @DisplayName("正常系: 車両リスト変換")
        void 車両リスト_変換() {
            RegisteredVehicleEntity e = RegisteredVehicleEntity.builder()
                    .userId(100L).vehicleType(VehicleType.MOTORCYCLE)
                    .plateNumber("enc".getBytes()).plateNumberHash("h").build();

            List<VehicleResponse> result = mapper.toVehicleResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getVehicleType()).isEqualTo("MOTORCYCLE");
        }
    }

    // ===================== Assignment =====================

    @Nested
    @DisplayName("toAssignmentResponse")
    class ToAssignmentResponse {

        @Test
        @DisplayName("正常系: 割り当てが変換される")
        void 割り当て_変換() {
            ParkingAssignmentEntity entity = ParkingAssignmentEntity.builder()
                    .spaceId(1L).vehicleId(10L).userId(100L).assignedBy(200L)
                    .contractStartDate(LocalDate.of(2025, 1, 1))
                    .contractEndDate(LocalDate.of(2025, 12, 31)).build();

            AssignmentResponse result = mapper.toAssignmentResponse(entity);

            assertThat(result.getSpaceId()).isEqualTo(1L);
            assertThat(result.getUserId()).isEqualTo(100L);
            assertThat(result.getContractStartDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        }

        @Test
        @DisplayName("正常系: 割り当てリスト変換")
        void 割り当てリスト_変換() {
            ParkingAssignmentEntity e = ParkingAssignmentEntity.builder()
                    .spaceId(1L).userId(100L).assignedBy(200L).build();

            List<AssignmentResponse> result = mapper.toAssignmentResponseList(List.of(e));

            assertThat(result).hasSize(1);
        }
    }

    // ===================== Application =====================

    @Nested
    @DisplayName("toApplicationResponse")
    class ToApplicationResponse {

        @Test
        @DisplayName("正常系: 申請が変換される")
        void 申請_変換() {
            ParkingApplicationEntity entity = ParkingApplicationEntity.builder()
                    .spaceId(1L).userId(100L).vehicleId(10L)
                    .sourceType(ApplicationSourceType.VACANCY)
                    .status(ParkingApplicationStatus.PENDING)
                    .priority(1).message("よろしくお願いします").build();

            ApplicationResponse result = mapper.toApplicationResponse(entity);

            assertThat(result.getSpaceId()).isEqualTo(1L);
            assertThat(result.getSourceType()).isEqualTo("VACANCY");
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getMessage()).isEqualTo("よろしくお願いします");
        }

        @Test
        @DisplayName("正常系: 申請リスト変換")
        void 申請リスト_変換() {
            ParkingApplicationEntity e = ParkingApplicationEntity.builder()
                    .spaceId(1L).userId(100L).vehicleId(10L)
                    .sourceType(ApplicationSourceType.LISTING).build();

            List<ApplicationResponse> result = mapper.toApplicationResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSourceType()).isEqualTo("LISTING");
        }
    }

    // ===================== Listing =====================

    @Nested
    @DisplayName("toListingResponse")
    class ToListingResponse {

        @Test
        @DisplayName("正常系: 譲渡希望が変換される")
        void 譲渡希望_変換() {
            ParkingListingEntity entity = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L)
                    .reason("転勤のため").desiredTransferDate(LocalDate.of(2025, 3, 31))
                    .status(ListingStatus.OPEN).build();

            ListingResponse result = mapper.toListingResponse(entity);

            assertThat(result.getSpaceId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo("OPEN");
            assertThat(result.getReason()).isEqualTo("転勤のため");
        }

        @Test
        @DisplayName("正常系: 譲渡希望詳細が変換される")
        void 譲渡希望詳細_変換() {
            ParkingListingEntity entity = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L)
                    .status(ListingStatus.RESERVED).build();

            ListingDetailResponse result = mapper.toListingDetailResponse(entity);

            assertThat(result.getStatus()).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("正常系: 譲渡希望リスト変換")
        void 譲渡希望リスト_変換() {
            ParkingListingEntity e = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L)
                    .status(ListingStatus.CANCELLED).build();

            List<ListingResponse> result = mapper.toListingResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("CANCELLED");
        }
    }

    // ===================== Visitor Reservation =====================

    @Nested
    @DisplayName("toVisitorReservationResponse")
    class ToVisitorReservationResponse {

        @Test
        @DisplayName("正常系: 来場者予約が変換される")
        void 来場者予約_変換() {
            ParkingVisitorReservationEntity entity = ParkingVisitorReservationEntity.builder()
                    .spaceId(1L).reservedBy(100L).visitorName("田中太郎")
                    .visitorPlateNumber("品川 123 あ 1234")
                    .reservedDate(LocalDate.of(2025, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0))
                    .purpose("打ち合わせ")
                    .status(VisitorReservationStatus.PENDING_APPROVAL).build();

            VisitorReservationResponse result = mapper.toVisitorReservationResponse(entity);

            assertThat(result.getSpaceId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo("PENDING_APPROVAL");
            assertThat(result.getVisitorName()).isEqualTo("田中太郎");
        }

        @Test
        @DisplayName("正常系: 来場者予約リスト変換")
        void 来場者予約リスト_変換() {
            ParkingVisitorReservationEntity e = ParkingVisitorReservationEntity.builder()
                    .spaceId(1L).reservedBy(100L)
                    .reservedDate(LocalDate.of(2025, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0))
                    .status(VisitorReservationStatus.CONFIRMED).build();

            List<VisitorReservationResponse> result = mapper.toVisitorReservationResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("CONFIRMED");
        }
    }

    // ===================== Watchlist =====================

    @Nested
    @DisplayName("toWatchlistResponse")
    class ToWatchlistResponse {

        @Test
        @DisplayName("正常系: ウォッチリストが変換される (spaceType有り)")
        void ウォッチリスト_spaceType有_変換() {
            ParkingWatchlistEntity entity = ParkingWatchlistEntity.builder()
                    .userId(100L).scopeType("TEAM").scopeId(1L)
                    .spaceType(SpaceType.INDOOR).floor("2F")
                    .maxPrice(BigDecimal.valueOf(15000)).isActive(true).build();

            WatchlistResponse result = mapper.toWatchlistResponse(entity);

            assertThat(result.getUserId()).isEqualTo(100L);
            assertThat(result.getSpaceType()).isEqualTo("INDOOR");
            assertThat(result.getFloor()).isEqualTo("2F");
        }

        @Test
        @DisplayName("正常系: ウォッチリストが変換される (spaceType無し)")
        void ウォッチリスト_spaceTypeNull_変換() {
            ParkingWatchlistEntity entity = ParkingWatchlistEntity.builder()
                    .userId(100L).scopeType("ORG").scopeId(1L).isActive(false).build();

            WatchlistResponse result = mapper.toWatchlistResponse(entity);

            assertThat(result.getSpaceType()).isNull();
        }

        @Test
        @DisplayName("正常系: ウォッチリストリスト変換")
        void ウォッチリストリスト_変換() {
            ParkingWatchlistEntity e = ParkingWatchlistEntity.builder()
                    .userId(100L).scopeType("TEAM").scopeId(1L).isActive(true).build();

            List<WatchlistResponse> result = mapper.toWatchlistResponseList(List.of(e));

            assertThat(result).hasSize(1);
        }
    }

    // ===================== Price History =====================

    @Nested
    @DisplayName("toPriceHistoryResponse")
    class ToPriceHistoryResponse {

        @Test
        @DisplayName("正常系: 料金履歴が変換される")
        void 料金履歴_変換() {
            ParkingSpacePriceHistoryEntity entity = ParkingSpacePriceHistoryEntity.builder()
                    .spaceId(1L).oldPrice(BigDecimal.valueOf(10000))
                    .newPrice(BigDecimal.valueOf(12000)).changedBy(200L).build();

            PriceHistoryResponse result = mapper.toPriceHistoryResponse(entity);

            assertThat(result.getSpaceId()).isEqualTo(1L);
            assertThat(result.getOldPrice()).isEqualByComparingTo(BigDecimal.valueOf(10000));
            assertThat(result.getNewPrice()).isEqualByComparingTo(BigDecimal.valueOf(12000));
        }

        @Test
        @DisplayName("正常系: 料金履歴リスト変換")
        void 料金履歴リスト_変換() {
            ParkingSpacePriceHistoryEntity e = ParkingSpacePriceHistoryEntity.builder()
                    .spaceId(1L).changedBy(200L).build();

            List<PriceHistoryResponse> result = mapper.toPriceHistoryResponseList(List.of(e));

            assertThat(result).hasSize(1);
        }
    }

    // ===================== Visitor Recurring =====================

    @Nested
    @DisplayName("toVisitorRecurringResponse")
    class ToVisitorRecurringResponse {

        @Test
        @DisplayName("正常系: 定期来場者予約テンプレートが変換される")
        void 定期来場者_変換() {
            ParkingVisitorRecurringEntity entity = ParkingVisitorRecurringEntity.builder()
                    .userId(100L).spaceId(1L).scopeType("TEAM").scopeId(1L)
                    .recurrenceType(RecurrenceType.WEEKLY).dayOfWeek(2)
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(10, 0))
                    .visitorName("鈴木花子").purpose("会議")
                    .isActive(true).nextGenerateDate(LocalDate.of(2025, 4, 1)).build();

            VisitorRecurringResponse result = mapper.toVisitorRecurringResponse(entity);

            assertThat(result.getUserId()).isEqualTo(100L);
            assertThat(result.getRecurrenceType()).isEqualTo("WEEKLY");
            assertThat(result.getDayOfWeek()).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系: 定期来場者予約リスト変換")
        void 定期来場者リスト_変換() {
            ParkingVisitorRecurringEntity e = ParkingVisitorRecurringEntity.builder()
                    .userId(100L).spaceId(1L).scopeType("TEAM").scopeId(1L)
                    .recurrenceType(RecurrenceType.MONTHLY).dayOfMonth(15)
                    .timeFrom(LocalTime.of(10, 0)).timeTo(LocalTime.of(11, 0))
                    .isActive(true).nextGenerateDate(LocalDate.of(2025, 5, 15)).build();

            List<VisitorRecurringResponse> result = mapper.toVisitorRecurringResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRecurrenceType()).isEqualTo("MONTHLY");
        }
    }

    // ===================== Settings =====================

    @Nested
    @DisplayName("toSettingsResponse")
    class ToSettingsResponse {

        @Test
        @DisplayName("正常系: 設定が変換される")
        void 設定_変換() {
            ParkingSettingsEntity entity = ParkingSettingsEntity.builder()
                    .scopeType("TEAM").scopeId(1L)
                    .maxSpacesPerUser(2)
                    .maxVisitorReservationsPerDay(5)
                    .visitorReservationMaxDaysAhead(60)
                    .visitorReservationRequiresApproval(false).build();

            ParkingSettingsResponse result = mapper.toSettingsResponse(entity);

            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getMaxSpacesPerUser()).isEqualTo(2);
            assertThat(result.getVisitorReservationRequiresApproval()).isFalse();
        }
    }

    // ===================== Sublease =====================

    @Nested
    @DisplayName("toSubleaseResponse")
    class ToSubleaseResponse {

        @Test
        @DisplayName("正常系: サブリースが変換される")
        void サブリース_変換() {
            ParkingSubleaseEntity entity = ParkingSubleaseEntity.builder()
                    .spaceId(1L).assignmentId(5L).offeredBy(100L)
                    .title("短期転貸")
                    .pricePerMonth(BigDecimal.valueOf(8000))
                    .paymentMethod(PaymentMethod.STRIPE)
                    .availableFrom(LocalDate.of(2025, 4, 1))
                    .availableTo(LocalDate.of(2025, 6, 30))
                    .status(SubleaseStatus.OPEN).build();

            SubleaseResponse result = mapper.toSubleaseResponse(entity);

            assertThat(result.getSpaceId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo("OPEN");
            assertThat(result.getPaymentMethod()).isEqualTo("STRIPE");
        }

        @Test
        @DisplayName("正常系: サブリース詳細が変換される")
        void サブリース詳細_変換() {
            ParkingSubleaseEntity entity = ParkingSubleaseEntity.builder()
                    .spaceId(1L).assignmentId(5L).offeredBy(100L)
                    .title("長期転貸").description("詳細説明")
                    .pricePerMonth(BigDecimal.valueOf(9000))
                    .paymentMethod(PaymentMethod.DIRECT)
                    .availableFrom(LocalDate.of(2025, 7, 1))
                    .status(SubleaseStatus.MATCHED).build();

            SubleaseDetailResponse result = mapper.toSubleaseDetailResponse(entity);

            assertThat(result.getDescription()).isEqualTo("詳細説明");
            assertThat(result.getStatus()).isEqualTo("MATCHED");
            assertThat(result.getPaymentMethod()).isEqualTo("DIRECT");
        }

        @Test
        @DisplayName("正常系: サブリースリスト変換")
        void サブリースリスト_変換() {
            ParkingSubleaseEntity e = ParkingSubleaseEntity.builder()
                    .spaceId(1L).assignmentId(5L).offeredBy(100L)
                    .title("T").pricePerMonth(BigDecimal.valueOf(5000))
                    .paymentMethod(PaymentMethod.DIRECT)
                    .availableFrom(LocalDate.of(2025, 1, 1))
                    .status(SubleaseStatus.CANCELLED).build();

            List<SubleaseResponse> result = mapper.toSubleaseResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("CANCELLED");
        }
    }

    // ===================== Sublease Application =====================

    @Nested
    @DisplayName("toSubleaseApplicationResponse")
    class ToSubleaseApplicationResponse {

        @Test
        @DisplayName("正常系: サブリース申請が変換される")
        void サブリース申請_変換() {
            ParkingSubleaseApplicationEntity entity = ParkingSubleaseApplicationEntity.builder()
                    .subleaseId(1L).userId(100L).vehicleId(10L)
                    .message("申請します").status(SubleaseApplicationStatus.PENDING).build();

            SubleaseApplicationResponse result = mapper.toSubleaseApplicationResponse(entity);

            assertThat(result.getSubleaseId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("正常系: サブリース申請リスト変換")
        void サブリース申請リスト_変換() {
            ParkingSubleaseApplicationEntity e = ParkingSubleaseApplicationEntity.builder()
                    .subleaseId(1L).userId(100L).vehicleId(10L)
                    .status(SubleaseApplicationStatus.APPROVED).build();

            List<SubleaseApplicationResponse> result = mapper.toSubleaseApplicationResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("APPROVED");
        }
    }

    // ===================== Sublease Payment =====================

    @Nested
    @DisplayName("toSubleasePaymentResponse")
    class ToSubleasePaymentResponse {

        @Test
        @DisplayName("正常系: サブリース決済が変換される")
        void サブリース決済_変換() {
            ParkingSubleasePaymentEntity entity = ParkingSubleasePaymentEntity.builder()
                    .subleaseId(1L).payerUserId(100L).payeeUserId(200L)
                    .amount(BigDecimal.valueOf(8000))
                    .stripeFee(BigDecimal.valueOf(200))
                    .platformFee(BigDecimal.valueOf(400))
                    .platformFeeRate(BigDecimal.valueOf(0.05))
                    .netAmount(BigDecimal.valueOf(7400))
                    .billingMonth("2025-04")
                    .status(SubleasePaymentStatus.PENDING).build();

            SubleasePaymentResponse result = mapper.toSubleasePaymentResponse(entity);

            assertThat(result.getSubleaseId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(8000));
        }

        @Test
        @DisplayName("正常系: サブリース決済リスト変換")
        void サブリース決済リスト_変換() {
            ParkingSubleasePaymentEntity e = ParkingSubleasePaymentEntity.builder()
                    .subleaseId(1L).payerUserId(100L).payeeUserId(200L)
                    .amount(BigDecimal.valueOf(5000))
                    .stripeFee(BigDecimal.valueOf(100))
                    .platformFee(BigDecimal.valueOf(250))
                    .platformFeeRate(BigDecimal.valueOf(0.05))
                    .netAmount(BigDecimal.valueOf(4650))
                    .billingMonth("2025-05")
                    .status(SubleasePaymentStatus.SUCCEEDED).build();

            List<SubleasePaymentResponse> result = mapper.toSubleasePaymentResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("SUCCEEDED");
        }
    }
}
