package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F01.2.1 AC-G109（BE-UT）— 制限の記録（UPSERT と合成）と判定のユニットテスト。
 *
 * <p>合成の規則そのものは {@link TeamOrgAffiliationRestrictionComposerTest}。ここでは
 * 「行が無ければ作る → 行ロックを取って合成規則で置き換えるか決める」という記録の手順と、
 * 判定に固定 Clock の現在時刻を渡すこと（SQL の NOW() を使わない。§4.6）を確かめる。
 * 並行に記録しても1行に収束することは {@code TeamOrgApplicationCommittedIT}（AC-G135）が実 DB で確かめる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F01.2.1 AC-G109 制限の記録と判定")
class TeamOrgAffiliationRestrictionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
    private static final Long ORG = 11L;
    private static final Long TEAM = 22L;
    private static final Long OPERATOR = 33L;

    @Mock
    private TeamOrgAffiliationRestrictionRepository repository;

    private TeamOrgAffiliationRestrictionService service;

    @BeforeEach
    void setUp() {
        service = new TeamOrgAffiliationRestrictionService(repository, Clock.fixed(NOW, ZoneOffset.UTC), 24);
    }

    // ---------------------------------------------------------------------
    // 記録
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("取下げの記録: 24時間の COOLDOWN を、固定 Clock の現在時刻から数えたエポック秒で作る")
    void 取下げは24時間のCOOLDOWNを作る() {
        TeamOrgAffiliationRestrictionEntity created = row(TeamOrgAffiliationRestrictionKind.COOLDOWN,
                NOW.plus(Duration.ofHours(24)), TeamOrgAffiliationRestrictionReason.WITHDRAWN);
        when(repository.findForUpdate(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY))
                .thenReturn(Optional.of(created));

        service.recordWithdrawal(ORG, TEAM, OPERATOR);

        verify(repository).insertCooldownIfAbsent(any(), eq(ORG), eq(TEAM), eq("TEAM_APPLY"), eq("WITHDRAWN"),
                eq(NOW.plus(Duration.ofHours(24)).getEpochSecond()), eq(OPERATOR));
        assertThat(created.getKind()).isEqualTo(TeamOrgAffiliationRestrictionKind.COOLDOWN);
        assertThat(created.getRestrictedUntil()).as("作った直後の行は、合成で動かない").isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("既存が BLOCK のとき、COOLDOWN を記録しても BLOCK のまま（上書きしない）")
    void BLOCKはCOOLDOWNで上書きされない() {
        TeamOrgAffiliationRestrictionEntity existing = row(TeamOrgAffiliationRestrictionKind.BLOCK, null,
                TeamOrgAffiliationRestrictionReason.REJECTED);
        when(repository.findForUpdate(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY))
                .thenReturn(Optional.of(existing));

        service.record(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.WITHDRAWN, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                Duration.ofHours(24), OPERATOR);

        assertThat(existing.getKind()).isEqualTo(TeamOrgAffiliationRestrictionKind.BLOCK);
        assertThat(existing.getRestrictedUntil()).isNull();
        assertThat(existing.getReason()).as("理由も既存のまま").isEqualTo(TeamOrgAffiliationRestrictionReason.REJECTED);
    }

    @Test
    @DisplayName("既存が COOLDOWN のとき、BLOCK を記録すると BLOCK に置き換わり、期限は NULL になる")
    void COOLDOWNはBLOCKで置き換わる() {
        TeamOrgAffiliationRestrictionEntity existing = row(TeamOrgAffiliationRestrictionKind.COOLDOWN,
                NOW.plus(Duration.ofDays(29)), TeamOrgAffiliationRestrictionReason.REJECTED);
        when(repository.findForUpdate(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY))
                .thenReturn(Optional.of(existing));

        service.record(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.REJECTED, TeamOrgAffiliationRestrictionKind.BLOCK,
                null, OPERATOR);

        verify(repository).insertBlockIfAbsent(any(), eq(ORG), eq(TEAM), eq("TEAM_APPLY"), eq("REJECTED"),
                eq(OPERATOR));
        assertThat(existing.getKind()).isEqualTo(TeamOrgAffiliationRestrictionKind.BLOCK);
        assertThat(existing.getRestrictedUntil()).isNull();
    }

    @Test
    @DisplayName("COOLDOWN どうし: 既存のほうが遅く切れるなら既存を残し、新しいほうが遅く切れるなら置き換える")
    void COOLDOWNどうしは期限の遅いほう() {
        TeamOrgAffiliationRestrictionEntity thirtyDays = row(TeamOrgAffiliationRestrictionKind.COOLDOWN,
                NOW.plus(Duration.ofDays(30)), TeamOrgAffiliationRestrictionReason.REJECTED);
        when(repository.findForUpdate(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY))
                .thenReturn(Optional.of(thirtyDays));

        service.recordWithdrawal(ORG, TEAM, OPERATOR); // 24時間

        assertThat(thirtyDays.getRestrictedUntil()).as("30日の冷却を24時間で縮めない").isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(thirtyDays.getReason()).isEqualTo(TeamOrgAffiliationRestrictionReason.REJECTED);

        TeamOrgAffiliationRestrictionEntity oneHour = row(TeamOrgAffiliationRestrictionKind.COOLDOWN,
                NOW.plus(Duration.ofHours(1)), TeamOrgAffiliationRestrictionReason.CANCELLED);
        when(repository.findForUpdate(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY))
                .thenReturn(Optional.of(oneHour));

        service.recordWithdrawal(ORG, TEAM, OPERATOR); // 24時間

        assertThat(oneHour.getRestrictedUntil()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(oneHour.getReason()).isEqualTo(TeamOrgAffiliationRestrictionReason.WITHDRAWN);
    }

    // ---------------------------------------------------------------------
    // 判定
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("判定は固定 Clock の現在時刻を引数で渡す（有効な制限があれば TEAM_068・無ければ通る）")
    void 判定は固定Clockの現在時刻を渡す() {
        when(repository.countActive(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionKind.BLOCK, NOW)).thenReturn(1L);
        when(repository.countActive(ORG, TEAM, TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionKind.BLOCK, NOW)).thenReturn(0L);

        assertThat(service.isRestricted(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY)).isTrue();
        assertThatThrownBy(() -> service.assertNotRestricted(ORG, TEAM, TeamOrgAffiliationDirection.TEAM_APPLY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode()).isEqualTo("TEAM_068");
        // 向きが違えば別の判定（TEAM_APPLY の制限は ORG_INVITE を止めない）
        assertThat(service.isRestricted(ORG, TEAM, TeamOrgAffiliationDirection.ORG_INVITE)).isFalse();
    }

    private static TeamOrgAffiliationRestrictionEntity row(TeamOrgAffiliationRestrictionKind kind, Instant until,
                                                          TeamOrgAffiliationRestrictionReason reason) {
        return TeamOrgAffiliationRestrictionEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .direction(TeamOrgAffiliationDirection.TEAM_APPLY)
                .kind(kind)
                .restrictedUntil(until)
                .reason(reason)
                .build();
    }
}
