package com.mannschaft.app.membership.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.AssignPositionRequest;
import com.mannschaft.app.membership.dto.MemberPositionDto;
import com.mannschaft.app.membership.entity.MemberPositionEntity;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.entity.PositionEntity;
import com.mannschaft.app.membership.repository.MemberPositionRepository;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.PositionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.AdminRoleMutationLockService;
import com.mannschaft.app.role.service.RolePermissionCleanupService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-260821-1027 T4: 役職割当（{@link MembershipService#assignPosition}）の存在オラクル本文正規化（試練）。
 *
 * <p>他スコープの役職 ID を指定した拒否と、不在の役職 ID の拒否が、同じエラーコード（= 同じ status・
 * error.code・message）になることを契約とする。修正前は前者が {@code MEMBERSHIP_POSITION_SCOPE_MISMATCH}、
 * 後者が {@code MEMBERSHIP_POSITION_CATALOG_NOT_FOUND} で割れており RED になる（status はどちらも404）。</p>
 *
 * <p>assignPosition には現時点で HTTP 入口が無い（呼出元は本 Service のみ。2026-10-31 grep で確認）。
 * そのため HTTP 契約・未認証401 は対象外とし、応答本文の素である ErrorCode の一致を Service 層で固定する。
 * ErrorCode→本文の写像は {@code GlobalExceptionHandler} が一意に決めるため、コードの一致は本文の一致を含意する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-260821-1027 T4 役職割当の越境/不在 本文一致契約")
class MembershipPositionScopeBodyParityTest {

    private static final long MEMBERSHIP_ID = 11L;
    private static final long OWN_SCOPE = 100L;

    @Mock private RolePermissionCleanupService rolePermissionCleanupService;
    @Mock private MembershipRepository membershipRepository;
    @Mock private MembershipScopeQueryService membershipScopeQueryService;
    @Mock private MemberPositionRepository memberPositionRepository;
    @Mock private PositionRepository positionRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private UserRowLockService userRowLockService;
    @Mock private AdminRoleMutationLockService adminRoleMutationLockService;
    @Mock private EntityManager entityManager;
    @InjectMocks private MembershipService service;

    @Test
    @DisplayName("AC-1027-T4-1: 他スコープ（他TEAM・同IDの組織）の役職IDと不在の役職IDで同じエラーコード・message になる")
    void AC_1027_T4_1_越境と不在のコード一致() {
        given(membershipRepository.findById(MEMBERSHIP_ID)).willReturn(Optional.of(membership()));
        given(positionRepository.findById(404L)).willReturn(Optional.empty());
        given(positionRepository.findById(31L)).willReturn(Optional.of(position(31L, ScopeType.TEAM, 999L)));
        given(positionRepository.findById(32L)).willReturn(Optional.of(position(32L, ScopeType.ORGANIZATION, OWN_SCOPE)));

        ErrorCode missing = reject(404L);
        for (long foreign : new long[] {31L, 32L}) {
            ErrorCode crossed = reject(foreign);
            assertThat(crossed.getCode()).as("positionId=%d の error.code", foreign).isEqualTo(missing.getCode());
            assertThat(crossed.getMessage()).as("positionId=%d の message", foreign).isEqualTo(missing.getMessage());
        }
    }

    @Test
    @DisplayName("AC-1027-T4-2: 越境の割当は member_positions へ書き込まない")
    void AC_1027_T4_2_越境はDB不変() {
        given(membershipRepository.findById(MEMBERSHIP_ID)).willReturn(Optional.of(membership()));
        given(positionRepository.findById(31L)).willReturn(Optional.of(position(31L, ScopeType.TEAM, 999L)));
        reject(31L);
        verify(memberPositionRepository, never()).save(any(MemberPositionEntity.class));
    }

    @Test
    @DisplayName("AC-1027-T4-3: 同スコープの有効な役職は割り当てられる")
    void AC_1027_T4_3_同スコープ正常系() {
        given(membershipRepository.findById(MEMBERSHIP_ID)).willReturn(Optional.of(membership()));
        given(positionRepository.findById(31L)).willReturn(Optional.of(position(31L, ScopeType.TEAM, OWN_SCOPE)));
        given(memberPositionRepository.save(any(MemberPositionEntity.class))).willAnswer(inv -> {
            MemberPositionEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 12001L);
            return e;
        });
        AssignPositionRequest req = new AssignPositionRequest();
        req.setPositionId(31L);
        MemberPositionDto dto = service.assignPosition(MEMBERSHIP_ID, req);
        assertThat(dto.positionId()).isEqualTo(31L);
    }

    private ErrorCode reject(long positionId) {
        AssignPositionRequest req = new AssignPositionRequest();
        req.setPositionId(positionId);
        BusinessException ex = catchThrowableOfType(() -> service.assignPosition(MEMBERSHIP_ID, req), BusinessException.class);
        assertThat(ex).as("positionId=%d は拒否される", positionId).isNotNull();
        return ex.getErrorCode();
    }

    private static MembershipEntity membership() {
        MembershipEntity e = MembershipEntity.builder().userId(99L).scopeType(ScopeType.TEAM).scopeId(OWN_SCOPE)
                .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now().minusMonths(1)).build();
        ReflectionTestUtils.setField(e, "id", MEMBERSHIP_ID);
        return e;
    }

    private static PositionEntity position(long id, ScopeType scopeType, long scopeId) {
        PositionEntity p = PositionEntity.builder().scopeType(scopeType).scopeId(scopeId)
                .name("TREASURER").displayName("会計係").build();
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }
}
