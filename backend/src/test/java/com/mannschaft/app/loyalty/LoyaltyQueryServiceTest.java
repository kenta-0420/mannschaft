package com.mannschaft.app.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.AccessControlService.TeamUserKey;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.loyalty.entity.*;
import com.mannschaft.app.loyalty.repository.*;
import com.mannschaft.app.membership.service.CheckinLocationService;
import com.mannschaft.app.team.service.TeamService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Pageable;

/** 実Reader・実投影・実署名cursorを通し、自表repoと外部契約だけを置換する。 */
class LoyaltyQueryServiceTest {
    private final LoyaltyProgramRepository programs = mock(LoyaltyProgramRepository.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final LoyaltyRewardRepository rewards = mock(LoyaltyRewardRepository.class);
    private final LoyaltyAccountRepository accounts = mock(LoyaltyAccountRepository.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final LoyaltyAwardRequestRepository requests = mock(LoyaltyAwardRequestRepository.class);
    private final TeamService teams = mock(TeamService.class);
    private final UserService users = mock(UserService.class);
    private final CheckinLocationService locations = mock(CheckinLocationService.class);
    private final AccessControlService memberships = mock(AccessControlService.class);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
    private final LoyaltyCursorCodec cursor = new LoyaltyCursorCodec(new EncryptionService(new byte[32], new byte[32]));
    private LoyaltyQueryService query;

    @BeforeEach
    void prepare() {
        query = new LoyaltyQueryService(new LoyaltyQueryReader(new LoyaltyStore(programs, rules, rewards,
                accounts, transactions, requests)), new LoyaltyQueryExternalStates(teams, users, locations, memberships),
                Mappers.getMapper(LoyaltyMapper.class), cursor,
                new LoyaltyTime(Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC)),
                new LoyaltyRequestStatePolicy(), new LoyaltyEligibilityPolicy());
        when(programs.findByTeamId(10L)).thenReturn(Optional.of(program()));
        when(rules.findByTeamId(10L)).thenReturn(Optional.of(rule()));
        when(programs.findByTeamIdIn(any())).thenReturn(List.of(program()));
        when(rules.findByTeamIdIn(any())).thenReturn(List.of(rule()));
        when(teams.getActiveStates(any())).thenReturn(Map.of(10L, new TeamService.ActiveState(true, "チーム")));
        when(users.getActiveDisplayStates(any())).thenReturn(Map.of(1L, new UserService.ActiveDisplayState(true, "本人"),
                2L, new UserService.ActiveDisplayState(true, "申請者")));
        when(locations.getTeamLocationStates(any())).thenReturn(Map.of(20L,
                new CheckinLocationService.TeamLocationState(true, 10L, "現在受付")));
        when(memberships.getTeamMembershipStates(any())).thenReturn(Map.of(new TeamUserKey(10L, 1L),
                new AccessControlService.TeamMembershipState(true, true), new TeamUserKey(10L, 2L),
                new AccessControlService.TeamMembershipState(true, true)));
    }

    @Test void 既存口座は退会後も匿名チーム名で読め特典交換に在籍条件を課さない() {
        when(accounts.findByTeamIdAndUserId(10L, 1L)).thenReturn(Optional.of(account(100)));
        when(teams.getActiveStates(any())).thenReturn(Map.of());
        when(memberships.getTeamMembershipStates(any())).thenReturn(Map.of());
        var result = query.account(1L, 10L);
        assertThat(result.balance()).isEqualTo(100);
        assertThat(result.teamName()).isEqualTo("利用終了したチーム");
        assertThat(result.isEligibleForAward()).isFalse();
    }

    @Test void 口座なしの非所属者へ設定や空履歴を漏らさない() {
        when(memberships.getTeamMembershipStates(any())).thenReturn(Map.of());
        assertThatThrownBy(() -> query.account(1L, 10L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.transactions(1L, 10L, null, 10)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transactions);
    }

    @Test void 在籍者の未作成口座は永続化せずゼロを返す() {
        var result = query.account(1L, 10L);
        assertThat(result.accountId()).isNull();
        assertThat(result.balance()).isZero();
        assertThat(result.isEligibleForAward()).isTrue();
        verify(accounts, never()).save(any());
    }

    @Test void QR検証済bootstrapは非所属の仮想口座を返すが通常読取権を残さない() {
        when(memberships.getTeamMembershipStates(any())).thenReturn(Map.of());
        assertThat(query.verifiedBootstrapAccount(1L, 10L).balance()).isZero();
        assertThatThrownBy(() -> query.account(1L, 10L)).isInstanceOf(BusinessException.class);
        verify(accounts, never()).save(any());
    }

    @Test void 口座一覧は本人に束縛し追加一件を次カーソルに含めない() {
        var first = account(20); var peek = account(30);
        when(accounts.firstPage(eq(1L), any())).thenReturn(List.of(first, peek));
        var page = query.listAccounts(1L, null, 1);
        assertThat(page.getData()).hasSize(1);
        assertThat(page.getMeta().isHasNext()).isTrue();
        assertThat(cursor.decode(new LoyaltyCursorCodec.Context(LoyaltyCursorCodec.Stream.ACCOUNTS, 1L, null),
                page.getMeta().getNextCursor()).id()).isEqualTo(first.getId());
        verify(accounts).firstPage(eq(1L), argThat(p -> p.getPageSize() == 2));
        verify(teams, times(1)).getActiveStates(any());
    }

    @Test void 設定未作成は既定値と空リストになる() {
        when(programs.findByTeamId(10L)).thenReturn(Optional.empty());
        when(rules.findByTeamId(10L)).thenReturn(Optional.empty());
        var value = query.settings(10L);
        assertThat(value.id()).isNull(); assertThat(value.version()).isNull();
        assertThat(value.isEnabled()).isFalse();
        assertThat(value.targetAudience()).isEqualTo(Audience.MEMBERS_AND_ABOVE);
        assertThat(value.selfAwardMode()).isEqualTo(SelfAwardMode.STAFF_APPROVAL);
        assertThat(value.rules()).isEmpty(); assertThat(value.rewards()).isEmpty();
    }

    @Test void 停止プログラムも有効ルールのカタログは返す() {
        when(programs.findByTeamId(10L)).thenReturn(Optional.of(LoyaltyProgramEntity.builder().teamId(10L)
                .isEnabled(false).targetAudience(Audience.ALL_AUTHENTICATED).selfAwardMode(SelfAwardMode.STAFF_APPROVAL).version(1).build()));
        var value = query.operationCatalog(10L);
        assertThat(value.isEnabled()).isFalse(); assertThat(value.rule()).isNotNull();
    }

    @Test void PENDING候補は上限まで検査して空ページでも最終検査位置から再開する() {
        var a = request(1); var b = request(2); var peek = request(3);
        when(requests.pendingFirstPage(eq(10L), eq(1L), eq(1L), eq(now.toLocalDate()), eq(now), any()))
                .thenReturn(List.of(a, b, peek));
        when(users.getActiveDisplayStates(any())).thenReturn(Map.of());
        var page = query.staffRequests(1L, 10L, RequestFilter.PENDING, null, 1);
        assertThat(page.getData()).isEmpty(); assertThat(page.getMeta().isHasNext()).isTrue();
        var position = cursor.decode(new LoyaltyCursorCodec.Context(LoyaltyCursorCodec.Stream.STAFF_PENDING, 1L, 10L),
                page.getMeta().getNextCursor());
        assertThat(position.id()).isEqualTo(b.getId()); assertThat(position.expiresAt()).isEqualTo(b.getExpiresAt());
        verify(requests).pendingFirstPage(eq(10L), eq(1L), eq(1L), any(), any(),
                argThat(p -> p.getPageSize() == 3));
        verify(teams, times(1)).getActiveStates(any()); verify(users, times(1)).getActiveDisplayStates(any());
        verify(locations, times(1)).getTeamLocationStates(any());
        verify(memberships, times(1)).getTeamMembershipStates(any());
        verify(accounts, times(1)).findByTeamIdInAndUserIdIn(any(), any());
        verify(transactions, times(1)).findByTeamIdInAndUserIdInAndAwardOn(any(), any(), any());
    }

    @Test void PENDINGは出力上限に達した実検査位置をcursorにする() {
        var a = request(1); var b = request(2); var c = request(3);
        when(requests.pendingFirstPage(any(), anyLong(), anyLong(), any(), any(), any())).thenReturn(List.of(a,b,c));
        var page = query.staffRequests(1L, 10L, RequestFilter.PENDING, null, 1);
        assertThat(page.getData()).extracting(r -> r.id()).containsExactly(a.getId());
        assertThat(page.getData().getFirst().canApprove()).isTrue();
        assertThat(cursor.decode(new LoyaltyCursorCodec.Context(LoyaltyCursorCodec.Stream.STAFF_PENDING, 1L, 10L),
                page.getMeta().getNextCursor()).id()).isEqualTo(a.getId());
    }

    @Test void 最終候補がすべて無効なら空の最終ページになる() {
        when(requests.pendingFirstPage(any(), anyLong(), anyLong(), any(), any(), any())).thenReturn(List.of(request(1)));
        when(users.getActiveDisplayStates(any())).thenReturn(Map.of());
        var page = query.staffRequests(1L, 10L, RequestFilter.PENDING, null, 1);
        assertThat(page.getData()).isEmpty(); assertThat(page.getMeta().isHasNext()).isFalse();
        assertThat(page.getMeta().getNextCursor()).isNull();
    }

    @Test void 本人申請とTEAM申請の詳細はrepo条件で束縛する() {
        assertThatThrownBy(() -> query.myRequest(1L, new UUID(0,1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.staffRequest(1L, 10L, new UUID(0,1))).isInstanceOf(BusinessException.class);
        verify(requests).findByIdAndUserId(new UUID(0,1),1L);
        verify(requests).findByIdAndTeamId(new UUID(0,1),10L);
    }

    @Test void 非公開PIIは現在snapshotが無効なら保存表示名へ戻さない() {
        var row = request(1);
        when(requests.findByIdAndUserId(row.getId(), 2L)).thenReturn(Optional.of(row));
        when(users.getActiveDisplayStates(any())).thenReturn(Map.of());
        when(locations.getTeamLocationStates(any())).thenReturn(Map.of());
        var value = query.myRequest(2L, row.getId());
        assertThat(value.applicantDisplayName()).isEqualTo("退会済み利用者");
        assertThat(value.locationName()).isEqualTo("利用終了した受付");
        assertThat(value.reason()).isEqualTo(RequestReason.LOCATION_INACTIVE);
        assertThat(row.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(row.getClosedAt()).isNull(); assertThat(value.closedAt()).isNull();
        verify(requests, never()).save(any());
    }

    @Test void 異なるTEAMの現在受付は有効受付と扱わない() {
        var row = request(1); when(requests.findByIdAndTeamId(row.getId(),10L)).thenReturn(Optional.of(row));
        when(locations.getTeamLocationStates(any())).thenReturn(Map.of(20L,
                new CheckinLocationService.TeamLocationState(true,99L,"別TEAM")));
        assertThat(query.staffRequest(1L,10L,row.getId()).reason()).isEqualTo(RequestReason.LOCATION_INACTIVE);
    }

    @Test void 保存終端は現在資格が変わっても改変しない() {
        var row = request(1); row.close(RequestStatus.CANCELLED, RequestReason.USER_CANCELLED, 2L, RequestOperation.CANCEL, UUID.randomUUID(), null, null, now);
        when(requests.findByIdAndUserId(row.getId(),2L)).thenReturn(Optional.of(row));
        when(teams.getActiveStates(any())).thenReturn(Map.of());
        var value = query.myRequest(2L,row.getId());
        assertThat(value.status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(value.canApprove()).isFalse(); assertThat(value.canCancel()).isFalse();
    }

    @Test void cursorの本人TEAM用途とlimitを入口で検証する() {
        String wrong = cursor.encode(new LoyaltyCursorCodec.Context(LoyaltyCursorCodec.Stream.ACCOUNTS,2L,null),
                new LoyaltyCursorCodec.Position(new UUID(0,1),null));
        assertThatThrownBy(() -> query.listAccounts(1L,wrong,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.myRequests(1L,"",10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.listAccounts(1L,null,0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.listAccounts(1L,null,101)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(accounts, requests);
    }

    private LoyaltyProgramEntity program() { return LoyaltyProgramEntity.builder().teamId(10L).isEnabled(true)
            .targetAudience(Audience.MEMBERS_AND_ABOVE).selfAwardMode(SelfAwardMode.STAFF_APPROVAL).version(1).build(); }
    private LoyaltyRuleEntity rule() { return LoyaltyRuleEntity.builder().id(new UUID(0,9)).teamId(10L).name("来場")
            .points(5).isActive(true).isSelfAwardEnabled(true).version(1).build(); }
    private LoyaltyAccountEntity account(int balance) { return LoyaltyAccountEntity.builder().id(UUID.randomUUID())
            .teamId(10L).userId(1L).balance(balance).lifetimeEarned(balance).build(); }
    private LoyaltyAwardRequestEntity request(int id) { return LoyaltyAwardRequestEntity.builder().id(new UUID(0,id))
            .teamId(10L).userId(2L).ruleId(new UUID(0,9)).sourceLocationId(20L).programVersion(1).ruleVersion(1)
            .points(5).ruleName("来場").locationName("保存受付名").awardOn(now.toLocalDate())
            .expiresAt(now.plusMinutes(10)).status(RequestStatus.PENDING).version(0).build(); }
}
