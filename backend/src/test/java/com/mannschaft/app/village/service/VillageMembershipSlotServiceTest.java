package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** current scalarの境界値。実SQL・悲観ロック・RR競合はAdmissionTransactionITで確認する。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("村USER枠のcurrent projection境界")
class VillageMembershipSlotServiceTest {
    private static final Long USER_ID = 42L;

    @Mock
    private UserRowLockService userRowLockService;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @InjectMocks
    private VillageMembershipSlotService service;

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
    @DisplayName("99枠から100枠目へ進める")
    void hundredthSlotIsAvailable() {
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID)).thenReturn(VillageMembershipSlotTestFixture.occupied(99));
        assertThat(service.allocate(USER_ID)).isEqualTo(new VillageMembershipSlotService.Allocation((short) 100, 100));
    }

    @ParameterizedTest
    @MethodSource("invalidRows")
    @DisplayName("NULL・0・101・重複slotは上限エラーへ隠さず不変条件違反にする")
    void refusesInvalidProjection(List<VillageMembershipRepository.AdmissionSlotRow> rows) {
        when(membershipRepository.findAdmissionSlotsForUpdate(USER_ID)).thenReturn(rows);
        assertThatThrownBy(() -> service.allocate(USER_ID)).isInstanceOf(IllegalStateException.class);
    }

    static Stream<List<VillageMembershipRepository.AdmissionSlotRow>> invalidRows() {
        return Stream.of(List.of(VillageMembershipSlotTestFixture.slot(null)),
                List.of(VillageMembershipSlotTestFixture.slot((short) 0)),
                List.of(VillageMembershipSlotTestFixture.slot((short) 101)),
                List.of(VillageMembershipSlotTestFixture.slot((short) 1), VillageMembershipSlotTestFixture.slot((short) 1)));
    }

    @ParameterizedTest
    @EnumSource(value = UserRowLockService.UserState.class, names = {"INELIGIBLE_EXISTING", "ABSENT"})
    @DisplayName("非現役・不在USERは入村根の取得成功にしない")
    void inactiveRootCannotProceed(UserRowLockService.UserState state) {
        when(userRowLockService.lock(USER_ID)).thenReturn(state);
        assertThat(service.lockUser(USER_ID)).isFalse();
        verifyNoInteractions(membershipRepository);
    }

    @Test
    @DisplayName("本人IDの現役USER行ロックを使う")
    void locksExactUserRoot() {
        when(userRowLockService.lock(USER_ID)).thenReturn(UserRowLockService.UserState.ACTIVE);
        assertThat(service.lockUser(USER_ID)).isTrue();
        verify(userRowLockService).lock(USER_ID);
    }
}
