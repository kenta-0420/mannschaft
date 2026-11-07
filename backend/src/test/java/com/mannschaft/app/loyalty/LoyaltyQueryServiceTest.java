package com.mannschaft.app.loyalty;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.AccessControlService.TeamUserKey;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import com.mannschaft.app.loyalty.repository.LoyaltyAccountRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyAwardRequestRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyProgramRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyRuleRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyRewardRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyTransactionRepository;
import com.mannschaft.app.membership.service.CheckinLocationService;
import com.mannschaft.app.team.service.TeamService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

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
    private final Instant now = Instant.parse("2026-10-16T03:00:00Z");
    private final LoyaltyCursorCodec cursor = new LoyaltyCursorCodec(new EncryptionService(new byte[32], new byte[32]));
    private LoyaltyQueryService query;
    private LocaleContext previousLocale;

    @BeforeEach
    void prepare() {
        previousLocale = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(java.util.Locale.JAPANESE);
        query = new LoyaltyQueryService(new LoyaltyQueryReader(new LoyaltyStore(programs, rules, rewards,
                accounts, transactions, requests)), new LoyaltyQueryExternalStates(teams, users, locations, memberships,
                new com.mannschaft.app.config.I18nConfig().messageSource()),
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

    @AfterEach void restoreLocale() {
        if (previousLocale == null) LocaleContextHolder.resetLocaleContext();
        else LocaleContextHolder.setLocaleContext(previousLocale);
    }

    @Test void 英語localeではシステム汎用名三種と別TEAM受付の代替名も英語になる() {
        LocaleContext before = LocaleContextHolder.getLocaleContext();
        try {
            LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
            var row = request(1);
            when(requests.findByIdAndUserId(row.getId(), 2L)).thenReturn(Optional.of(row));
            when(teams.getActiveStates(any())).thenReturn(Map.of());
            when(users.getActiveDisplayStates(any())).thenReturn(Map.of());
            when(locations.getTeamLocationStates(any())).thenReturn(Map.of(20L,
                    new CheckinLocationService.TeamLocationState(true, 99L, "別TEAM設定名")));
            var value = query.myRequest(2L, row.getId());
            assertThat(value).isNotNull();
            assertThat(value.teamName()).isEqualTo("Team no longer available");
            assertThat(value.applicantDisplayName()).isEqualTo("Former user");
            assertThat(value.locationName()).isEqualTo("Location no longer available");
            assertThat(value.reason()).isEqualTo(RequestReason.TEAM_INACTIVE);
        } finally {
            if (before == null) LocaleContextHolder.resetLocaleContext();
            else LocaleContextHolder.setLocaleContext(before);
        }
    }

    @Test void 既存口座は退会後も匿名チーム名で読める() {
        when(accounts.findByTeamIdAndUserId(10L, 1L)).thenReturn(Optional.of(account(100)));
        when(teams.getActiveStates(any())).thenReturn(Map.of());
        when(memberships.getTeamMembershipStates(any())).thenReturn(Map.of());
        var result = query.account(1L, 10L);
        assertThat(result).isNotNull();
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
        assertThat(result).isNotNull();
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
        assertThat(page).isNotNull();
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
        assertThat(value).isNotNull();
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
        assertThat(value).isNotNull();
        assertThat(value.isEnabled()).isFalse(); assertThat(value.rule()).isNotNull();
    }

    @Test void PENDING候補は上限まで検査して空ページでも最終検査位置から再開する() {
        var a = request(1); var b = request(2); var peek = request(3);
        when(requests.pendingFirstPage(eq(10L), eq(1L), eq(1L), eq(LocalDate.ofInstant(now, SERVER_ZONE)), eq(now), any()))
                .thenReturn(List.of(a, b, peek));
        when(users.getActiveDisplayStates(any())).thenReturn(Map.of());
        var page = query.staffRequests(1L, 10L, RequestFilter.PENDING, null, 1);
        assertThat(page).isNotNull();
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
        assertThat(page).isNotNull();
        assertThat(page.getData()).extracting(r -> r.id()).containsExactly(a.getId());
        assertThat(page.getData().getFirst().canApprove()).isTrue();
        assertThat(cursor.decode(new LoyaltyCursorCodec.Context(LoyaltyCursorCodec.Stream.STAFF_PENDING, 1L, 10L),
                page.getMeta().getNextCursor()).id()).isEqualTo(a.getId());
    }

    @Test void 最終候補がすべて無効なら空の最終ページになる() {
        when(requests.pendingFirstPage(any(), anyLong(), anyLong(), any(), any(), any())).thenReturn(List.of(request(1)));
        when(users.getActiveDisplayStates(any())).thenReturn(Map.of());
        var page = query.staffRequests(1L, 10L, RequestFilter.PENDING, null, 1);
        assertThat(page).isNotNull();
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
        assertThat(value).isNotNull();
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
        assertThat(value).isNotNull();
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

    @Test void 現在表示名がnull空でも保存PIIへ戻さず必須文字列を返す() {
        var row = request(1);
        when(requests.findByIdAndUserId(row.getId(), 2L)).thenReturn(Optional.of(row));
        when(teams.getActiveStates(any())).thenReturn(Map.of(10L, new TeamService.ActiveState(true, null)));
        when(users.getActiveDisplayStates(any())).thenReturn(Map.of(2L, new UserService.ActiveDisplayState(true, " ")));
        when(locations.getTeamLocationStates(any())).thenReturn(Map.of(20L,
                new CheckinLocationService.TeamLocationState(true,10L,null)));
        var value = query.myRequest(2L,row.getId());
        assertThat(value).isNotNull();
        assertThat(value.teamName()).isEqualTo("利用終了したチーム");
        assertThat(value.applicantDisplayName()).isEqualTo("退会済み利用者");
        assertThat(value.locationName()).isEqualTo("利用終了した受付");
    }

    @Test void TEAM必須入口はnullを未関係ゼロや既定設定として返さない() {
        assertThatThrownBy(() -> query.account(1L,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.transactions(1L,null,null,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.staffRequests(1L,null,RequestFilter.ALL,null,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.staffRequest(1L,null,new UUID(0,1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.balance(null,1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(accounts, requests);
    }

    @Test void 特典進捗は在籍資格で交換を抑止せず不足をゼロ下限にする() {
        when(accounts.findByTeamIdAndUserId(10L,1L)).thenReturn(Optional.of(account(100)));
        when(memberships.getTeamMembershipStates(any())).thenReturn(Map.of());
        when(rewards.findByTeamIdOrderById(10L)).thenReturn(List.of(
                reward(1,80,true),reward(2,150,true),reward(3,10,false)));
        var value = query.account(1L,10L);
        assertThat(value).isNotNull(); assertThat(value.isEligibleForAward()).isFalse();
        assertThat(value.rewards()).hasSize(2);
        assertThat(value.rewards().getFirst().remainingPoints()).isZero();
        assertThat(value.rewards().getFirst().canRedeem()).isTrue();
        assertThat(value.rewards().getLast().remainingPoints()).isEqualTo(50);
        assertThat(value.rewards().getLast().canRedeem()).isFalse();
    }

    @Test void 設定は停止特典も返し操作カタログは有効特典だけ返す() {
        when(rewards.findByTeamIdOrderById(10L)).thenReturn(List.of(reward(1,80,true),reward(2,150,false)));
        assertThat(query.settings(10L).rewards()).hasSize(2);
        assertThat(query.operationCatalog(10L).rewards()).hasSize(1);
        when(programs.findByTeamId(10L)).thenReturn(Optional.empty());
        var absent = query.operationCatalog(10L);
        assertThat(absent).isNotNull(); assertThat(absent.rule()).isNull();
        assertThat(absent.programVersion()).isNull(); assertThat(absent.rewards()).isEmpty();
    }

    @Test void 取引は本人TEAMと降順cursorへ束縛し確定時ラベルを保持する() {
        when(accounts.findByTeamIdAndUserId(10L,1L)).thenReturn(Optional.of(account(100)));
        var first = LoyaltyTransactionEntity.builder().id(new UUID(0,3)).teamId(10L).userId(1L)
                .label("当時の特典名").kind(TransactionKind.REDEEM).build();
        var peek = LoyaltyTransactionEntity.builder().id(new UUID(0,2)).teamId(10L).userId(1L).build();
        when(transactions.firstPage(eq(10L),eq(1L),any())).thenReturn(List.of(first,peek));
        var page = query.transactions(1L,10L,null,1);
        assertThat(page).isNotNull(); assertThat(page.getData().getFirst().label()).isEqualTo("当時の特典名");
        var next = page.getMeta().getNextCursor(); assertThat(next).isNotBlank();
        query.transactions(1L,10L,next,1);
        verify(transactions).firstPage(eq(10L),eq(1L),argThat(p -> p.getPageSize()==2));
        verify(transactions).nextPage(eq(10L),eq(1L),eq(first.getId()),any());
        assertThatThrownBy(() -> query.transactions(1L,11L,next,1)).isInstanceOf(BusinessException.class);
    }

    @Test void 本人申請一覧は除外せず投影し本人取消だけ許す() {
        var first = request(3); var peek = request(2);
        when(requests.myFirstPage(eq(2L),any())).thenReturn(List.of(first,peek));
        var page = query.myRequests(2L,null,1);
        assertThat(page).isNotNull(); assertThat(page.getData()).hasSize(1);
        assertThat(page.getData().getFirst().canApprove()).isFalse();
        assertThat(page.getData().getFirst().canCancel()).isTrue();
        query.myRequests(2L,page.getMeta().getNextCursor(),1);
        verify(requests).myNextPage(eq(2L),eq(first.getId()),any());
    }

    @Test void 日次と残高snapshotは申請者の組だけで投影しGETを保存しない() {
        var row = request(1);
        when(requests.findByIdAndTeamId(row.getId(),10L)).thenReturn(Optional.of(row));
        when(accounts.findByTeamIdInAndUserIdIn(any(),any())).thenReturn(List.of(
                LoyaltyAccountEntity.builder().teamId(10L).userId(2L).balance(Integer.MAX_VALUE).build()));
        assertThat(query.staffRequest(1L,10L,row.getId()).reason()).isEqualTo(RequestReason.POINT_LIMIT);
        var other = LoyaltyTransactionEntity.builder().teamId(10L).userId(99L).ruleId(row.getRuleId())
                .awardOn(row.getAwardOn()).build();
        when(transactions.findByTeamIdInAndUserIdInAndAwardOn(any(),any(),any())).thenReturn(List.of(other));
        assertThat(query.staffRequest(1L,10L,row.getId()).reason()).isEqualTo(RequestReason.POINT_LIMIT);
        var own = LoyaltyTransactionEntity.builder().teamId(10L).userId(2L).ruleId(row.getRuleId())
                .awardOn(row.getAwardOn()).build();
        when(transactions.findByTeamIdInAndUserIdInAndAwardOn(any(),any(),any())).thenReturn(List.of(own));
        assertThat(query.staffRequest(1L,10L,row.getId()).reason()).isEqualTo(RequestReason.DAILY_AWARDED);
        assertThat(row.getStatus()).isEqualTo(RequestStatus.PENDING);
        verify(requests,never()).save(any());
    }

    @Test void 空ページでは外部照会せずpreview残高は本人TEAMだけ読む() {
        var page = query.listAccounts(1L,null,100);
        assertThat(page).isNotNull(); assertThat(page.getData()).isEmpty();
        assertThat(page.getMeta().isHasNext()).isFalse();
        verifyNoInteractions(teams,users,locations,memberships);
        assertThat(query.balance(10L,1L)).isZero();
        when(accounts.findByTeamIdAndUserId(10L,1L)).thenReturn(Optional.of(account(123)));
        assertThat(query.balance(10L,1L)).isEqualTo(123);
    }

    @Test void 公開snapshot障害を有効PENDINGや匿名表示で隠さない() {
        var row = request(1); when(requests.findByIdAndUserId(row.getId(),2L)).thenReturn(Optional.of(row));
        when(users.getActiveDisplayStates(any())).thenThrow(new IllegalStateException("test outage"));
        assertThatThrownBy(() -> query.myRequest(2L,row.getId())).isInstanceOf(IllegalStateException.class);
        verify(requests,never()).save(any());
    }

    private LoyaltyRewardEntity reward(int id, int points, boolean active) {
        return LoyaltyRewardEntity.builder().id(new UUID(0,id)).teamId(10L).name("特典"+id)
                .requiredPoints(points).isActive(active).version(1).build();
    }

    private LoyaltyProgramEntity program() { return LoyaltyProgramEntity.builder().teamId(10L).isEnabled(true)
            .targetAudience(Audience.MEMBERS_AND_ABOVE).selfAwardMode(SelfAwardMode.STAFF_APPROVAL).version(1).build(); }
    private LoyaltyRuleEntity rule() { return LoyaltyRuleEntity.builder().id(new UUID(0,9)).teamId(10L).name("来場")
            .points(5).isActive(true).isSelfAwardEnabled(true).version(1).build(); }
    private LoyaltyAccountEntity account(int balance) { return LoyaltyAccountEntity.builder().id(UUID.randomUUID())
            .teamId(10L).userId(1L).balance(balance).lifetimeEarned(balance).build(); }
    private LoyaltyAwardRequestEntity request(int id) { return LoyaltyAwardRequestEntity.builder().id(new UUID(0,id))
            .teamId(10L).userId(2L).ruleId(new UUID(0,9)).sourceLocationId(20L).programVersion(1).ruleVersion(1)
            .points(5).ruleName("来場").locationName("保存受付名").awardOn(LocalDate.ofInstant(now, SERVER_ZONE))
            .expiresAt(now.plus(10, ChronoUnit.MINUTES)).status(RequestStatus.PENDING).version(0).build(); }
}
