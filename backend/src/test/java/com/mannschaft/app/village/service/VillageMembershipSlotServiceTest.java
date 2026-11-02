package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageUserAdmissionLockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** current scalarの境界値。実SQL・悲観ロック・RR競合はAdmissionTransactionITで確認する。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("村USER枠のcurrent projection境界")
class VillageMembershipSlotServiceTest {
    private static final Long USER_ID = 42L;

    @Mock
    private VillageUserAdmissionLockRepository admissionLockRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    private VillageMembershipSlotService service;

    @org.junit.jupiter.api.BeforeEach
    void wireDefaultLimit() {
        service = new VillageMembershipSlotService(admissionLockRepository, membershipRepository,
                new VillageParticipationLimitProvider(null));
    }

    @Test
    @DisplayName("0枠ならslot1・保存後1件を返す")
    void emptyStartsAtOne() {
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID)).thenReturn(List.of());
        assertThat(service.allocate(USER_ID)).isEqualTo(new VillageMembershipSlotService.Allocation((short) 1, 1));
    }

    @Test
    @DisplayName("件数+1ではなく最小の穴を使う")
    void picksSmallestHole() {
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID)).thenReturn(List.of(
                VillageMembershipSlotTestFixture.slot((short) 1), VillageMembershipSlotTestFixture.slot((short) 3)));
        assertThat(service.allocate(USER_ID)).isEqualTo(new VillageMembershipSlotService.Allocation((short) 2, 3));
        verify(membershipRepository).findAdmissionSlotsForUpdate(USER_ID);
    }

    @Test
    @DisplayName("既定上限-1枠から既定上限枠目へ進める")
    void hundredthSlotIsAvailable() {
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID)).thenReturn(VillageMembershipSlotTestFixture.occupied(VillageParticipationLimits.DEFAULT_HARD_LIMIT - 1));
        assertThat(service.allocate(USER_ID)).isEqualTo(
                new VillageMembershipSlotService.Allocation((short) VillageParticipationLimits.DEFAULT_HARD_LIMIT, VillageParticipationLimits.DEFAULT_HARD_LIMIT));
    }

    @ParameterizedTest
    @MethodSource("invalidRows")
    @DisplayName("NULL・0・DB天井+1・重複slotは上限エラーへ隠さず不変条件違反にする")
    void refusesInvalidProjection(List<VillageMembershipRepository.AdmissionSlotRow> rows) {
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID)).thenReturn(rows);
        assertThatThrownBy(() -> service.allocate(USER_ID)).isInstanceOf(IllegalStateException.class);
    }

    static Stream<List<VillageMembershipRepository.AdmissionSlotRow>> invalidRows() {
        return Stream.of(List.of(VillageMembershipSlotTestFixture.slot(null)),
                List.of(VillageMembershipSlotTestFixture.slot((short) 0)),
                List.of(VillageMembershipSlotTestFixture.slot((short) (VillageParticipationLimits.DB_SLOT_CEILING + 1))),
                List.of(VillageMembershipSlotTestFixture.slot((short) 1), VillageMembershipSlotTestFixture.slot((short) 1)));
    }

    @Test
    @DisplayName("USER IDが無ければ入村ロックを取らない")
    void nullUserCannotProceed() {
        assertThat(service.lockUser(null)).isFalse();
        verifyNoInteractions(admissionLockRepository, membershipRepository);
    }

    @Test
    @DisplayName("村ドメインのUSER単位ロック行を本人IDで取る（auth の users 行は使わない）")
    void locksVillageAdmissionRowOfExactUser() {
        assertThat(service.lockUser(USER_ID)).isTrue();
        verify(admissionLockRepository).lockForAdmission(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(USER_ID));
        verifyNoInteractions(membershipRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "Duplicate entry 'x' for key 'village_memberships.uk_vm_active_subject', ALREADY_MEMBER",
            "Duplicate entry 'x' for key 'village_memberships.uk_vm_village_subject', ALREADY_MEMBER",
            "Duplicate entry 'x' for key 'village_memberships.uk_vm_user_active_slot', PARTICIPATION_LIMIT_EXCEEDED",
            "Check constraint 'ck_vm_active_user_slot' is violated., PARTICIPATION_LIMIT_EXCEEDED"
    })
    @DisplayName("AC-1808-4: 入村保存のDB制約違反を500にせず業務コードへ写像する")
    void admitMapsConstraintViolation(String message, VillageErrorCode expected) {
        VillageMembershipEntity entity = VillageMembershipEntity.builder().build();
        when(membershipRepository.save(entity)).thenReturn(entity);
        doThrow(new DataIntegrityViolationException("could not execute statement",
                new java.sql.SQLIntegrityConstraintViolationException(message)))
                .when(membershipRepository).flush();
        assertThatThrownBy(() -> service.admit(entity))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("AC-1808-4: MySQL の CHECK 違反（変換表に無い error 3819）も上限超過へ写像する")
    void admitMapsUncategorizedCheckViolation() {
        VillageMembershipEntity entity = VillageMembershipEntity.builder().build();
        when(membershipRepository.save(entity)).thenReturn(entity);
        doThrow(new org.springframework.jdbc.UncategorizedSQLException("flush", "INSERT",
                new java.sql.SQLException("Check constraint 'ck_vm_active_user_slot' is violated.", "HY000", 3819)))
                .when(membershipRepository).flush();
        assertThatThrownBy(() -> service.admit(entity))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("AC-1808-4: 入村と無関係な制約違反は業務コードへ丸めず再送出する")
    void admitRethrowsUnrelatedViolation() {
        VillageMembershipEntity entity = VillageMembershipEntity.builder().build();
        when(membershipRepository.save(entity)).thenReturn(entity);
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("fk_vm_village");
        doThrow(unrelated).when(membershipRepository).flush();
        assertThatThrownBy(() -> service.admit(entity)).isSameAs(unrelated);
    }

    @Test
    @DisplayName("入村保存は同じTX内でflushして制約違反をその場で検出する")
    void admitFlushesImmediately() {
        VillageMembershipEntity entity = VillageMembershipEntity.builder().build();
        when(membershipRepository.save(entity)).thenReturn(entity);
        assertThat(service.admit(entity)).isSameAs(entity);
        verify(membershipRepository).flush();
    }

    @Test
    @DisplayName("AC-1808-6: 退村扱いのBAN行が残っていれば現役行の有無を問わずMEMBER_BANNED")
    void bannedHistoryRejectsRejoin() {
        java.util.UUID village = java.util.UUID.randomUUID();
        when(membershipRepository.findAdmissionPresenceForUpdate(village, "USER", USER_ID)).thenReturn(List.of(
                VillageMembershipSlotTestFixture.presence(true), VillageMembershipSlotTestFixture.presence(true)));
        assertThatThrownBy(() -> service.ensureNotCurrentMember(village,
                com.mannschaft.app.village.entity.enums.VillageSubjectType.USER, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.MEMBER_BANNED));
    }

    @Test
    @DisplayName("BAN行が無く現役行だけならALREADY_MEMBER、行が無ければ通す")
    void activeWithoutBanIsAlreadyMember() {
        java.util.UUID village = java.util.UUID.randomUUID();
        when(membershipRepository.findAdmissionPresenceForUpdate(village, "USER", USER_ID))
                .thenReturn(List.of(VillageMembershipSlotTestFixture.presence(false)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.ensureNotCurrentMember(village,
                com.mannschaft.app.village.entity.enums.VillageSubjectType.USER, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.ALREADY_MEMBER));
        service.ensureNotCurrentMember(village, com.mannschaft.app.village.entity.enums.VillageSubjectType.USER, USER_ID);
    }

    @Test
    @DisplayName("既定上限に達していれば、文言へ現在の上限値を差し込んで拒否する")
    void rejectsAtDefaultLimitWithCurrentValue() {
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID))
                .thenReturn(VillageMembershipSlotTestFixture.occupied(VillageParticipationLimits.DEFAULT_HARD_LIMIT));
        assertThatThrownBy(() -> service.allocate(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED);
                    assertThat(error.getMessageArgs())
                            .containsExactly(String.valueOf(VillageParticipationLimits.DEFAULT_HARD_LIMIT));
                    assertThat(error.getMessage())
                            .contains(String.valueOf(VillageParticipationLimits.DEFAULT_HARD_LIMIT))
                            .doesNotContain("{0}");
                });
    }

    @Test
    @DisplayName("上限を下げると、既存の所属は残したまま上限以上の利用者の新しい入村だけを止める")
    void loweredLimitBlocksOnlyNewAdmissions() {
        int lowered = VillageParticipationLimits.DEFAULT_HARD_LIMIT - 1;
        VillageMembershipSlotService loweredService = new VillageMembershipSlotService(
                admissionLockRepository, membershipRepository, new VillageParticipationLimitProvider(lowered));
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID))
                .thenReturn(VillageMembershipSlotTestFixture.occupied(lowered))
                .thenReturn(VillageMembershipSlotTestFixture.occupied(lowered - 1));

        assertThatThrownBy(() -> loweredService.allocate(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getMessageArgs()).containsExactly(String.valueOf(lowered)));
        assertThat(loweredService.allocate(USER_ID))
                .isEqualTo(new VillageMembershipSlotService.Allocation((short) lowered, lowered));
    }

    @Test
    @DisplayName("上限を上げると、既定上限を超えて天井まで枠を割り当てる")
    void raisedLimitAllocatesBeyondDefault() {
        VillageMembershipSlotService raisedService = new VillageMembershipSlotService(
                admissionLockRepository, membershipRepository,
                new VillageParticipationLimitProvider(VillageParticipationLimits.DB_SLOT_CEILING));
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID))
                .thenReturn(VillageMembershipSlotTestFixture.occupied(VillageParticipationLimits.DEFAULT_HARD_LIMIT));
        assertThat(raisedService.allocate(USER_ID)).isEqualTo(new VillageMembershipSlotService.Allocation(
                (short) (VillageParticipationLimits.DEFAULT_HARD_LIMIT + 1), VillageParticipationLimits.DEFAULT_HARD_LIMIT + 1));
    }
}
