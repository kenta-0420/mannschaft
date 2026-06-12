package com.mannschaft.app.tournament.scorekeeper;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.scorekeeper.dto.ScorekeeperResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F08.7 順位UI 項目③ — {@link TournamentScorekeeperService} 指名管理の契約テスト。
 *
 * <p>一覧／追加／削除はすべて主催組織 ADMIN 限定であること、他組織の大会は 404 に倒れること、
 * 既存指名は冪等に扱われることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentScorekeeperService — 指名管理（主催組織 ADMIN 限定）")
class TournamentScorekeeperServiceTest {

    private static final Long ORG_ID = 100L;
    private static final Long T_ID = 7L;
    private static final Long ADMIN = 1L;
    private static final Long NON_ADMIN = 9L;
    private static final Long TARGET = 50L;

    @Mock
    private TournamentScorekeeperRepository scorekeeperRepository;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TournamentScorekeeperService service;

    @BeforeEach
    void setUp() {
        TournamentEntity tournament = TournamentEntity.builder()
                .organizationId(ORG_ID).name("T").createdBy(ADMIN).build();
        when(tournamentRepository.findById(T_ID)).thenReturn(Optional.of(tournament));
    }

    private void asAdmin(Long userId) {
        when(accessControlService.isSystemAdmin(userId)).thenReturn(false);
        when(accessControlService.isAdminOrAbove(userId, ORG_ID, "ORGANIZATION")).thenReturn(true);
    }

    private void asNonAdmin(Long userId) {
        when(accessControlService.isSystemAdmin(userId)).thenReturn(false);
        when(accessControlService.isAdminOrAbove(userId, ORG_ID, "ORGANIZATION")).thenReturn(false);
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("ADMIN は新規指名できる")
        void admin_adds() {
            asAdmin(ADMIN);
            when(scorekeeperRepository.findByTournamentIdAndUserId(T_ID, TARGET)).thenReturn(Optional.empty());
            when(scorekeeperRepository.save(any())).thenAnswer(inv -> {
                TournamentScorekeeperEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            ScorekeeperResponse res = service.addScorekeeper(ORG_ID, T_ID, ADMIN, TARGET);

            assertThat(res.userId()).isEqualTo(TARGET);
            assertThat(res.createdBy()).isEqualTo(ADMIN);
            verify(scorekeeperRepository).save(any());
        }

        @Test
        @DisplayName("既に指名済みなら冪等に既存を返し save しない")
        void admin_idempotent() {
            asAdmin(ADMIN);
            TournamentScorekeeperEntity existing = TournamentScorekeeperEntity.builder()
                    .tournamentId(T_ID).userId(TARGET).createdBy(ADMIN).build();
            existing.setId(UUID.randomUUID());
            when(scorekeeperRepository.findByTournamentIdAndUserId(T_ID, TARGET))
                    .thenReturn(Optional.of(existing));

            ScorekeeperResponse res = service.addScorekeeper(ORG_ID, T_ID, ADMIN, TARGET);

            assertThat(res.userId()).isEqualTo(TARGET);
            verify(scorekeeperRepository, never()).save(any());
        }

        @Test
        @DisplayName("非 ADMIN は 403（SCOREKEEPER_MANAGE_FORBIDDEN）")
        void nonAdmin_forbidden() {
            asNonAdmin(NON_ADMIN);
            assertThatThrownBy(() -> service.addScorekeeper(ORG_ID, T_ID, NON_ADMIN, TARGET))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(TournamentErrorCode.SCOREKEEPER_MANAGE_FORBIDDEN);
            verify(scorekeeperRepository, never()).save(any());
        }

        @Test
        @DisplayName("他組織の大会は 404")
        void wrongOrg_notFound() {
            assertThatThrownBy(() -> service.addScorekeeper(999L, T_ID, ADMIN, TARGET))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("list")
    class ListScorekeepers {

        @Test
        @DisplayName("ADMIN は一覧取得できる")
        void admin_lists() {
            asAdmin(ADMIN);
            TournamentScorekeeperEntity e = TournamentScorekeeperEntity.builder()
                    .tournamentId(T_ID).userId(TARGET).createdBy(ADMIN).build();
            e.setId(UUID.randomUUID());
            when(scorekeeperRepository.findByTournamentIdOrderByCreatedAtAsc(T_ID)).thenReturn(List.of(e));

            assertThat(service.listScorekeepers(ORG_ID, T_ID, ADMIN)).hasSize(1);
        }

        @Test
        @DisplayName("非 ADMIN は 403")
        void nonAdmin_forbidden() {
            asNonAdmin(NON_ADMIN);
            assertThatThrownBy(() -> service.listScorekeepers(ORG_ID, T_ID, NON_ADMIN))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(TournamentErrorCode.SCOREKEEPER_MANAGE_FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("ADMIN は指名解除できる")
        void admin_removes() {
            asAdmin(ADMIN);
            UUID skId = UUID.randomUUID();
            TournamentScorekeeperEntity e = TournamentScorekeeperEntity.builder()
                    .tournamentId(T_ID).userId(TARGET).createdBy(ADMIN).build();
            e.setId(skId);
            when(scorekeeperRepository.findByIdAndTournamentId(skId, T_ID)).thenReturn(Optional.of(e));

            service.removeScorekeeper(ORG_ID, T_ID, ADMIN, skId);

            verify(scorekeeperRepository).delete(e);
        }

        @Test
        @DisplayName("存在しない指名は 404（SCOREKEEPER_NOT_FOUND）")
        void notFound() {
            asAdmin(ADMIN);
            UUID skId = UUID.randomUUID();
            when(scorekeeperRepository.findByIdAndTournamentId(skId, T_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeScorekeeper(ORG_ID, T_ID, ADMIN, skId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(TournamentErrorCode.SCOREKEEPER_NOT_FOUND);
        }

        @Test
        @DisplayName("非 ADMIN は 403")
        void nonAdmin_forbidden() {
            asNonAdmin(NON_ADMIN);
            assertThatThrownBy(() -> service.removeScorekeeper(ORG_ID, T_ID, NON_ADMIN, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(TournamentErrorCode.SCOREKEEPER_MANAGE_FORBIDDEN);
        }
    }
}
