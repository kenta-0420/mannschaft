package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.TeamApplicationGroupMode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F01.2.1 §6.1 step 7・§6.9 — 加盟の PENDING を作る経路の固定順ロック（チーム行 → 組織行）と、
 * ロック取得後の状態の再確認のユニットテスト。
 *
 * <p>並行したときの実際の直列化（9件からの2件・10件からの2件）は
 * {@code TeamOrgApplicationCommittedIT}（AC-B17）が実 DB で確かめる。ここでは、順序が本クラスの1か所に固定され、
 * 逆順（組織行 → チーム行）にならないこと、削除・アーカイブされた相手に対して状態を変えないことを確かめる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F01.2.1 §6.9 チーム行→組織行の固定順ロックと状態の再確認")
class TeamOrgAffiliationLockSupportTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamAffiliationOrganizationPort organizationPort;

    private TeamOrgAffiliationLockSupport lockSupport;

    @BeforeEach
    void setUp() {
        lockSupport = new TeamOrgAffiliationLockSupport(teamRepository, organizationPort);
    }

    @Test
    @DisplayName("チーム行を先に、組織行を後にロックする（逆順にならない）")
    void チーム行が先で組織行が後() {
        TeamEntity team = mock(TeamEntity.class);
        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(team));
        when(organizationPort.lockForAffiliation(ORG_ID)).thenReturn(state(false));

        TeamOrgAffiliationLockSupport.LockedScope scope = lockSupport.lockTeamThenOrganization(TEAM_ID, ORG_ID);

        InOrder order = inOrder(teamRepository, organizationPort);
        order.verify(teamRepository).findByIdForUpdate(TEAM_ID);
        order.verify(organizationPort).lockForAffiliation(ORG_ID);
        assertThat(scope.team()).isSameAs(team);
        assertThat(scope.organization().id()).isEqualTo(ORG_ID);
    }

    @Test
    @DisplayName("チームが削除済み（ロックで取れない）なら 404 TEAM_001 で、組織行はロックしない")
    void 削除済みチームは404で組織行をロックしない() {
        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lockSupport.lockTeamThenOrganization(TEAM_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode()).isEqualTo("TEAM_001");
        verify(organizationPort, never()).lockForAffiliation(ORG_ID);
    }

    @Test
    @DisplayName("チームがアーカイブ済みなら TEAM_002（ロック取得後の再確認）")
    void アーカイブ済みチームはTEAM_002() {
        TeamEntity archived = mock(TeamEntity.class);
        when(archived.getArchivedAt()).thenReturn(LocalDateTime.now());
        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(archived));
        when(organizationPort.lockForAffiliation(ORG_ID)).thenReturn(state(false));

        assertThatThrownBy(() -> lockSupport.lockTeamThenOrganization(TEAM_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode()).isEqualTo("TEAM_002");
    }

    @Test
    @DisplayName("組織がアーカイブ済みなら ORG_003（ロック取得後の再確認）")
    void アーカイブ済み組織はORG_003() {
        TeamEntity team = mock(TeamEntity.class);
        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(team));
        when(organizationPort.lockForAffiliation(ORG_ID)).thenReturn(state(true));

        assertThatThrownBy(() -> lockSupport.lockTeamThenOrganization(TEAM_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode()).isEqualTo("ORG_003");
    }

    private static TeamAffiliationOrganizationPort.OrganizationAffiliationState state(boolean archived) {
        return new TeamAffiliationOrganizationPort.OrganizationAffiliationState(
                ORG_ID, "org-slug", "組織", archived, true, false, TeamApplicationGroupMode.OFF);
    }
}
