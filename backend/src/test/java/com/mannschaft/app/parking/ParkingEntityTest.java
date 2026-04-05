package com.mannschaft.app.parking;

import com.mannschaft.app.parking.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * parking モジュールの Entity ビジネスメソッドのテスト。
 */
@DisplayName("Parking Entity 単体テスト")
class ParkingEntityTest {

    // ===================== ParkingSpaceEntity =====================

    @Nested
    @DisplayName("ParkingSpaceEntity")
    class ParkingSpaceEntityTests {

        @Test
        @DisplayName("update: フィールドが更新される")
        void update_更新() {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                    .createdBy(100L).build();

            entity.update("B-001", SpaceType.OUTDOOR, "屋外", BigDecimal.valueOf(8000), "2F", "雨天注意");

            assertThat(entity.getSpaceNumber()).isEqualTo("B-001");
            assertThat(entity.getSpaceType()).isEqualTo(SpaceType.OUTDOOR);
            assertThat(entity.getSpaceTypeLabel()).isEqualTo("屋外");
            assertThat(entity.getPricePerMonth()).isEqualByComparingTo(BigDecimal.valueOf(8000));
            assertThat(entity.getFloor()).isEqualTo("2F");
            assertThat(entity.getNotes()).isEqualTo("雨天注意");
        }

        @Test
        @DisplayName("changeStatus: ステータスが変更される")
        void changeStatus_変更() {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                    .createdBy(100L).build();

            entity.changeStatus(SpaceStatus.OCCUPIED);

            assertThat(entity.getStatus()).isEqualTo(SpaceStatus.OCCUPIED);
        }

        @Test
        @DisplayName("acceptApplications: 申請受付が開始される")
        void acceptApplications_開始() {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                    .createdBy(100L).build();

            LocalDateTime deadline = LocalDateTime.of(2025, 12, 31, 23, 59);
            entity.acceptApplications(AllocationMethod.LOTTERY, deadline);

            assertThat(entity.getApplicationStatus()).isEqualTo(ApplicationStatus.ACCEPTING);
            assertThat(entity.getAllocationMethod()).isEqualTo(AllocationMethod.LOTTERY);
            assertThat(entity.getApplicationDeadline()).isEqualTo(deadline);
        }

        @Test
        @DisplayName("closeLottery: 申請受付がLOTTERY_CLOSEDになる")
        void closeLottery_締切() {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.ACCEPTING)
                    .createdBy(100L).build();

            entity.closeLottery();

            assertThat(entity.getApplicationStatus()).isEqualTo(ApplicationStatus.LOTTERY_CLOSED);
        }

        @Test
        @DisplayName("resetApplicationStatus: 申請受付がリセットされる")
        void resetApplicationStatus_リセット() {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.ACCEPTING)
                    .allocationMethod(AllocationMethod.LOTTERY)
                    .applicationDeadline(LocalDateTime.now().plusDays(10))
                    .createdBy(100L).build();

            entity.resetApplicationStatus();

            assertThat(entity.getApplicationStatus()).isEqualTo(ApplicationStatus.NOT_ACCEPTING);
            assertThat(entity.getAllocationMethod()).isNull();
            assertThat(entity.getApplicationDeadline()).isNull();
        }

        @Test
        @DisplayName("softDelete: deletedAtが設定される")
        void softDelete_設定() {
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType("TEAM").scopeId(1L).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                    .createdBy(100L).build();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    // ===================== ParkingApplicationEntity =====================

    @Nested
    @DisplayName("ParkingApplicationEntity")
    class ParkingApplicationEntityTests {

        @Test
        @DisplayName("approve: ステータスがAPPROVEDになる")
        void approve_承認() {
            ParkingApplicationEntity entity = ParkingApplicationEntity.builder()
                    .spaceId(1L).userId(100L).vehicleId(10L).build();

            entity.approve();

            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.APPROVED);
            assertThat(entity.getDecidedAt()).isNotNull();
        }

        @Test
        @DisplayName("reject: ステータスがREJECTEDになり理由が設定される")
        void reject_拒否() {
            ParkingApplicationEntity entity = ParkingApplicationEntity.builder()
                    .spaceId(1L).userId(100L).vehicleId(10L).build();

            entity.reject("条件不一致");

            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.REJECTED);
            assertThat(entity.getRejectionReason()).isEqualTo("条件不一致");
            assertThat(entity.getDecidedAt()).isNotNull();
        }

        @Test
        @DisplayName("cancel: ステータスがCANCELLEDになる")
        void cancel_キャンセル() {
            ParkingApplicationEntity entity = ParkingApplicationEntity.builder()
                    .spaceId(1L).userId(100L).vehicleId(10L).build();

            entity.cancel();

            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.CANCELLED);
            assertThat(entity.getDecidedAt()).isNotNull();
        }

        @Test
        @DisplayName("markLotteryPending: 抽選待ちステータスになる")
        void markLotteryPending_抽選待ち() {
            ParkingApplicationEntity entity = ParkingApplicationEntity.builder()
                    .spaceId(1L).userId(100L).vehicleId(10L).build();

            entity.markLotteryPending(42);

            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.LOTTERY_PENDING);
            assertThat(entity.getLotteryNumber()).isEqualTo(42);
        }
    }

    // ===================== ParkingAssignmentEntity =====================

    @Nested
    @DisplayName("ParkingAssignmentEntity")
    class ParkingAssignmentEntityTests {

        @Test
        @DisplayName("release: 解除日時・理由が設定される")
        void release_解除() {
            ParkingAssignmentEntity entity = ParkingAssignmentEntity.builder()
                    .spaceId(1L).userId(100L).assignedBy(200L).build();

            entity.release(200L, "転勤のため");

            assertThat(entity.getReleasedAt()).isNotNull();
            assertThat(entity.getReleasedBy()).isEqualTo(200L);
            assertThat(entity.getReleaseReason()).isEqualTo("転勤のため");
        }

        @Test
        @DisplayName("isReleased: 解除済みの場合trueを返す")
        void isReleased_解除済みtrue() {
            ParkingAssignmentEntity entity = ParkingAssignmentEntity.builder()
                    .spaceId(1L).userId(100L).assignedBy(200L)
                    .releasedAt(LocalDateTime.now()).build();

            assertThat(entity.isReleased()).isTrue();
        }

        @Test
        @DisplayName("isReleased: 未解除の場合falseを返す")
        void isReleased_未解除false() {
            ParkingAssignmentEntity entity = ParkingAssignmentEntity.builder()
                    .spaceId(1L).userId(100L).assignedBy(200L).build();

            assertThat(entity.isReleased()).isFalse();
        }
    }

    // ===================== ParkingSubleaseEntity =====================

    @Nested
    @DisplayName("ParkingSubleaseEntity")
    class ParkingSubleaseEntityTests {

        @Test
        @DisplayName("update: フィールドが更新される")
        void update_更新() {
            ParkingSubleaseEntity entity = ParkingSubleaseEntity.builder()
                    .spaceId(1L).assignmentId(5L).offeredBy(100L)
                    .title("サブリース").pricePerMonth(BigDecimal.valueOf(8000))
                    .paymentMethod(PaymentMethod.DIRECT)
                    .availableFrom(LocalDate.of(2025, 4, 1)).build();

            entity.update("新サブリース", "詳細変更", BigDecimal.valueOf(9000),
                    PaymentMethod.STRIPE, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 8, 31));

            assertThat(entity.getTitle()).isEqualTo("新サブリース");
            assertThat(entity.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE);
            assertThat(entity.getPricePerMonth()).isEqualByComparingTo(BigDecimal.valueOf(9000));
        }

        @Test
        @DisplayName("match: MATCHEDステータスになる")
        void match_成立() {
            ParkingSubleaseEntity entity = ParkingSubleaseEntity.builder()
                    .spaceId(1L).assignmentId(5L).offeredBy(100L)
                    .title("T").pricePerMonth(BigDecimal.valueOf(5000))
                    .paymentMethod(PaymentMethod.DIRECT)
                    .availableFrom(LocalDate.of(2025, 1, 1)).build();

            entity.match(99L);

            assertThat(entity.getStatus()).isEqualTo(SubleaseStatus.MATCHED);
            assertThat(entity.getMatchedApplicationId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("cancel: CANCELLEDになる")
        void cancel_キャンセル() {
            ParkingSubleaseEntity entity = ParkingSubleaseEntity.builder()
                    .spaceId(1L).assignmentId(5L).offeredBy(100L)
                    .title("T").pricePerMonth(BigDecimal.valueOf(5000))
                    .paymentMethod(PaymentMethod.DIRECT)
                    .availableFrom(LocalDate.of(2025, 1, 1)).build();

            entity.cancel();

            assertThat(entity.getStatus()).isEqualTo(SubleaseStatus.CANCELLED);
        }

        @Test
        @DisplayName("softDelete: deletedAtが設定される")
        void softDelete_設定() {
            ParkingSubleaseEntity entity = ParkingSubleaseEntity.builder()
                    .spaceId(1L).assignmentId(5L).offeredBy(100L)
                    .title("T").pricePerMonth(BigDecimal.valueOf(5000))
                    .paymentMethod(PaymentMethod.DIRECT)
                    .availableFrom(LocalDate.of(2025, 1, 1)).build();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    // ===================== ParkingListingEntity =====================

    @Nested
    @DisplayName("ParkingListingEntity")
    class ParkingListingEntityTests {

        @Test
        @DisplayName("update: フィールドが更新される")
        void update_更新() {
            ParkingListingEntity entity = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L)
                    .reason("転勤").build();

            entity.update("引越し", LocalDate.of(2025, 3, 31));

            assertThat(entity.getReason()).isEqualTo("引越し");
            assertThat(entity.getDesiredTransferDate()).isEqualTo(LocalDate.of(2025, 3, 31));
        }

        @Test
        @DisplayName("reserve: RESERVEDステータスになる")
        void reserve_予約() {
            ParkingListingEntity entity = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L).build();

            entity.reserve(200L, 20L);

            assertThat(entity.getStatus()).isEqualTo(ListingStatus.RESERVED);
            assertThat(entity.getTransfereeUserId()).isEqualTo(200L);
            assertThat(entity.getTransfereeVehicleId()).isEqualTo(20L);
        }

        @Test
        @DisplayName("transfer: TRANSFERREDステータスになる")
        void transfer_譲渡() {
            ParkingListingEntity entity = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L).build();

            entity.transfer();

            assertThat(entity.getStatus()).isEqualTo(ListingStatus.TRANSFERRED);
            assertThat(entity.getTransferredAt()).isNotNull();
        }

        @Test
        @DisplayName("cancel: CANCELLEDになる")
        void cancel_キャンセル() {
            ParkingListingEntity entity = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L).build();

            entity.cancel();

            assertThat(entity.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        }

        @Test
        @DisplayName("softDelete: deletedAtが設定される")
        void softDelete_設定() {
            ParkingListingEntity entity = ParkingListingEntity.builder()
                    .spaceId(1L).assignmentId(5L).listedBy(100L).build();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    // ===================== ParkingSubleaseApplicationEntity =====================

    @Nested
    @DisplayName("ParkingSubleaseApplicationEntity")
    class ParkingSubleaseApplicationEntityTests {

        @Test
        @DisplayName("approve: APPROVEDになる")
        void approve_承認() {
            ParkingSubleaseApplicationEntity entity = ParkingSubleaseApplicationEntity.builder()
                    .subleaseId(1L).userId(100L).vehicleId(10L).build();

            entity.approve();

            assertThat(entity.getStatus()).isEqualTo(SubleaseApplicationStatus.APPROVED);
            assertThat(entity.getDecidedAt()).isNotNull();
        }

        @Test
        @DisplayName("reject: REJECTEDになる")
        void reject_拒否() {
            ParkingSubleaseApplicationEntity entity = ParkingSubleaseApplicationEntity.builder()
                    .subleaseId(1L).userId(100L).vehicleId(10L).build();

            entity.reject();

            assertThat(entity.getStatus()).isEqualTo(SubleaseApplicationStatus.REJECTED);
            assertThat(entity.getDecidedAt()).isNotNull();
        }

        @Test
        @DisplayName("cancel: CANCELLEDになる")
        void cancel_キャンセル() {
            ParkingSubleaseApplicationEntity entity = ParkingSubleaseApplicationEntity.builder()
                    .subleaseId(1L).userId(100L).vehicleId(10L).build();

            entity.cancel();

            assertThat(entity.getStatus()).isEqualTo(SubleaseApplicationStatus.CANCELLED);
            assertThat(entity.getDecidedAt()).isNotNull();
        }
    }

    // ===================== ParkingSubleasePaymentEntity =====================

    @Nested
    @DisplayName("ParkingSubleasePaymentEntity")
    class ParkingSubleasePaymentEntityTests {

        @Test
        @DisplayName("markSucceeded: SUCCEEDEDになりpaidAtが設定される")
        void markSucceeded_成功() {
            ParkingSubleasePaymentEntity entity = ParkingSubleasePaymentEntity.builder()
                    .subleaseId(1L).payerUserId(100L).payeeUserId(200L)
                    .amount(BigDecimal.valueOf(8000))
                    .stripeFee(BigDecimal.valueOf(200))
                    .platformFee(BigDecimal.valueOf(400))
                    .platformFeeRate(BigDecimal.valueOf(0.05))
                    .netAmount(BigDecimal.valueOf(7400))
                    .billingMonth("2025-04").build();

            entity.markSucceeded("pi_test123", "tr_test456");

            assertThat(entity.getStatus()).isEqualTo(SubleasePaymentStatus.SUCCEEDED);
            assertThat(entity.getStripePaymentIntentId()).isEqualTo("pi_test123");
            assertThat(entity.getStripeTransferId()).isEqualTo("tr_test456");
            assertThat(entity.getPaidAt()).isNotNull();
        }

        @Test
        @DisplayName("markFailed: FAILEDになり理由が設定される")
        void markFailed_失敗() {
            ParkingSubleasePaymentEntity entity = ParkingSubleasePaymentEntity.builder()
                    .subleaseId(1L).payerUserId(100L).payeeUserId(200L)
                    .amount(BigDecimal.valueOf(8000))
                    .stripeFee(BigDecimal.valueOf(200))
                    .platformFee(BigDecimal.valueOf(400))
                    .platformFeeRate(BigDecimal.valueOf(0.05))
                    .netAmount(BigDecimal.valueOf(7400))
                    .billingMonth("2025-04").build();

            entity.markFailed("カードの残高不足");

            assertThat(entity.getStatus()).isEqualTo(SubleasePaymentStatus.FAILED);
            assertThat(entity.getFailedReason()).isEqualTo("カードの残高不足");
        }
    }

    // ===================== ParkingVisitorReservationEntity =====================

    @Nested
    @DisplayName("ParkingVisitorReservationEntity")
    class ParkingVisitorReservationEntityTests {

        @Test
        @DisplayName("approve: CONFIRMEDになる")
        void approve_承認() {
            ParkingVisitorReservationEntity entity = ParkingVisitorReservationEntity.builder()
                    .spaceId(1L).reservedBy(100L)
                    .reservedDate(LocalDate.of(2025, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0)).build();

            entity.approve(200L);

            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.CONFIRMED);
            assertThat(entity.getApprovedBy()).isEqualTo(200L);
            assertThat(entity.getApprovedAt()).isNotNull();
        }

        @Test
        @DisplayName("reject: REJECTEDになる")
        void reject_拒否() {
            ParkingVisitorReservationEntity entity = ParkingVisitorReservationEntity.builder()
                    .spaceId(1L).reservedBy(100L)
                    .reservedDate(LocalDate.of(2025, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0)).build();

            entity.reject(200L, "満車のため");

            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.REJECTED);
            assertThat(entity.getAdminComment()).isEqualTo("満車のため");
        }

        @Test
        @DisplayName("checkIn: CHECKED_INになる")
        void checkIn_チェックイン() {
            ParkingVisitorReservationEntity entity = ParkingVisitorReservationEntity.builder()
                    .spaceId(1L).reservedBy(100L)
                    .reservedDate(LocalDate.of(2025, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0)).build();

            entity.checkIn();

            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.CHECKED_IN);
        }

        @Test
        @DisplayName("complete: COMPLETEDになる")
        void complete_完了() {
            ParkingVisitorReservationEntity entity = ParkingVisitorReservationEntity.builder()
                    .spaceId(1L).reservedBy(100L)
                    .reservedDate(LocalDate.of(2025, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0)).build();

            entity.complete();

            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.COMPLETED);
        }

        @Test
        @DisplayName("cancel: CANCELLEDになる")
        void cancel_キャンセル() {
            ParkingVisitorReservationEntity entity = ParkingVisitorReservationEntity.builder()
                    .spaceId(1L).reservedBy(100L)
                    .reservedDate(LocalDate.of(2025, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0)).build();

            entity.cancel();

            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.CANCELLED);
        }
    }

    // ===================== ParkingVisitorRecurringEntity =====================

    @Nested
    @DisplayName("ParkingVisitorRecurringEntity")
    class ParkingVisitorRecurringEntityTests {

        @Test
        @DisplayName("update: フィールドが更新される")
        void update_更新() {
            ParkingVisitorRecurringEntity entity = ParkingVisitorRecurringEntity.builder()
                    .userId(100L).spaceId(1L).scopeType("TEAM").scopeId(1L)
                    .recurrenceType(RecurrenceType.WEEKLY).dayOfWeek(1)
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(10, 0))
                    .isActive(true).nextGenerateDate(LocalDate.of(2025, 4, 7)).build();

            entity.update(RecurrenceType.MONTHLY, null, 15,
                    LocalTime.of(14, 0), LocalTime.of(15, 0),
                    "田中", "品川 1 あ 123", "月例会議");

            assertThat(entity.getRecurrenceType()).isEqualTo(RecurrenceType.MONTHLY);
            assertThat(entity.getDayOfMonth()).isEqualTo(15);
            assertThat(entity.getVisitorName()).isEqualTo("田中");
        }

        @Test
        @DisplayName("updateNextGenerateDate: 次回生成日が更新される")
        void updateNextGenerateDate_更新() {
            ParkingVisitorRecurringEntity entity = ParkingVisitorRecurringEntity.builder()
                    .userId(100L).spaceId(1L).scopeType("TEAM").scopeId(1L)
                    .recurrenceType(RecurrenceType.WEEKLY).dayOfWeek(1)
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(10, 0))
                    .isActive(true).nextGenerateDate(LocalDate.of(2025, 4, 7)).build();

            entity.updateNextGenerateDate(LocalDate.of(2025, 4, 14));

            assertThat(entity.getNextGenerateDate()).isEqualTo(LocalDate.of(2025, 4, 14));
        }

        @Test
        @DisplayName("deactivate: isActiveがfalseになる")
        void deactivate_無効化() {
            ParkingVisitorRecurringEntity entity = ParkingVisitorRecurringEntity.builder()
                    .userId(100L).spaceId(1L).scopeType("TEAM").scopeId(1L)
                    .recurrenceType(RecurrenceType.WEEKLY).dayOfWeek(1)
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(10, 0))
                    .isActive(true).nextGenerateDate(LocalDate.of(2025, 4, 7)).build();

            entity.deactivate();

            assertThat(entity.getIsActive()).isFalse();
        }
    }

    // ===================== RegisteredVehicleEntity =====================

    @Nested
    @DisplayName("RegisteredVehicleEntity")
    class RegisteredVehicleEntityTests {

        @Test
        @DisplayName("update: フィールドが更新される")
        void update_更新() {
            RegisteredVehicleEntity entity = RegisteredVehicleEntity.builder()
                    .userId(100L).vehicleType(VehicleType.CAR)
                    .plateNumber("old".getBytes()).plateNumberHash("oldhash")
                    .nickname("旧車").build();

            entity.update(VehicleType.MOTORCYCLE, "new".getBytes(), "newhash", "新バイク");

            assertThat(entity.getVehicleType()).isEqualTo(VehicleType.MOTORCYCLE);
            assertThat(entity.getNickname()).isEqualTo("新バイク");
        }

        @Test
        @DisplayName("softDelete: deletedAtが設定される")
        void softDelete_設定() {
            RegisteredVehicleEntity entity = RegisteredVehicleEntity.builder()
                    .userId(100L).vehicleType(VehicleType.CAR)
                    .plateNumber("p".getBytes()).plateNumberHash("h").build();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    // ===================== ParkingSettingsEntity =====================

    @Nested
    @DisplayName("ParkingSettingsEntity")
    class ParkingSettingsEntityTests {

        @Test
        @DisplayName("update: フィールドが更新される")
        void update_更新() {
            ParkingSettingsEntity entity = ParkingSettingsEntity.builder()
                    .scopeType("TEAM").scopeId(1L).maxSpacesPerUser(1)
                    .maxVisitorReservationsPerDay(2).visitorReservationMaxDaysAhead(30)
                    .visitorReservationRequiresApproval(true).build();

            entity.update(3, 5, 60, false);

            assertThat(entity.getMaxSpacesPerUser()).isEqualTo(3);
            assertThat(entity.getMaxVisitorReservationsPerDay()).isEqualTo(5);
            assertThat(entity.getVisitorReservationMaxDaysAhead()).isEqualTo(60);
            assertThat(entity.getVisitorReservationRequiresApproval()).isFalse();
        }
    }
}
