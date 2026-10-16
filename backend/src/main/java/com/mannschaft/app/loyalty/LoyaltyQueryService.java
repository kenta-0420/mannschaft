package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.CursorPagedResponse.CursorMeta;
import com.mannschaft.app.common.AccessControlService.TeamUserKey;
import com.mannschaft.app.loyalty.dto.AccountDetail;
import com.mannschaft.app.loyalty.dto.AccountSummary;
import com.mannschaft.app.loyalty.dto.AwardRequestResponse;
import com.mannschaft.app.loyalty.dto.OperationCatalog;
import com.mannschaft.app.loyalty.dto.ProgramSettings;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 自表の読取TXを完了してから公開Serviceへ集合照会する非TX段取り役。
 * actor active/staff/adminの入口認可はFacadeが行い、本人/TEAMの読取束縛はここでも維持する。
 */
@Service
@RequiredArgsConstructor
public class LoyaltyQueryService {
    private final LoyaltyQueryReader reader;
    private final LoyaltyQueryExternalStates external;
    private final LoyaltyMapper mapper;
    private final LoyaltyCursorCodec cursors;
    private final LoyaltyTime time;
    private final LoyaltyRequestStatePolicy states;
    private final LoyaltyEligibilityPolicy eligibility;

    public CursorPagedResponse<AccountSummary> listAccounts(Long actor, String cursor, int limit) {
        var context = context(LoyaltyCursorCodec.Stream.ACCOUNTS, actor, null, limit);
        var position = position(context, cursor);
        var rows = reader.accounts(actor, position == null ? null : position.id(), limit + 1);
        var page = rows.subList(0, Math.min(limit, rows.size()));
        var snapshot = external.read(page.stream().map(a -> a.getTeamId()).collect(Collectors.toSet()),
                Set.of(), Set.of(), Set.of());
        var data = page.stream().map(a -> mapper.accountSummary(a, snapshot.teamName(a.getTeamId()))).toList();
        return page(data, rows.size() > limit, limit, context,
                page.isEmpty() ? null : new LoyaltyCursorCodec.Position(page.getLast().getId(), null));
    }

    public AccountDetail account(Long actor, Long team) {
        return account(actor, team, false);
    }

    /** 検証済みQR経路だけで呼び、仮想口座を通常GET権へ昇格させない。 */
    public AccountDetail verifiedBootstrapAccount(Long actor, Long team) {
        return account(actor, team, true);
    }

    private AccountDetail account(Long actor, Long team, boolean bootstrap) {
        validateTeam(team);
        validate(actor, team);
        var own = reader.catalog(team, actor);
        var snapshot = external.read(Set.of(team), Set.of(actor), Set.of(), Set.of(new TeamUserKey(team, actor)));
        if (!bootstrap) requireReadable(own.account() != null, snapshot, team, actor);
        boolean enabled = own.program() != null && own.program().isEnabled() && snapshot.teamActive(team);
        boolean eligible = enabled && eligible(own.program(), snapshot, team, actor);
        int balance = own.account() == null ? 0 : own.account().getBalance();
        var rewards = own.rewards().stream().filter(r -> r.isActive()).map(r -> mapper.rewardProgress(r,
                Math.max(r.getRequiredPoints() - balance, 0), enabled && balance >= r.getRequiredPoints())).toList();
        return mapper.accountDetail(own.account(), team, snapshot.teamName(team), enabled, eligible, rewards);
    }

    public CursorPagedResponse<TransactionResponse> transactions(Long actor, Long team, String cursor, int limit) {
        var context = context(LoyaltyCursorCodec.Stream.TRANSACTIONS, actor, team, limit);
        var position = position(context, cursor);
        var account = reader.account(team, actor);
        if (account == null) {
            var snapshot = external.read(Set.of(team), Set.of(actor), Set.of(), Set.of(new TeamUserKey(team, actor)));
            requireReadable(false, snapshot, team, actor);
            return page(List.of(), false, limit, context, null);
        }
        var rows = reader.transactions(actor, team, position == null ? null : position.id(), limit + 1);
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        return page(selected.stream().map(mapper::transaction).toList(), rows.size() > limit, limit, context,
                selected.isEmpty() ? null : new LoyaltyCursorCodec.Position(selected.getLast().getId(), null));
    }

    public OperationCatalog operationCatalog(Long team) {
        validateTeam(team);
        var own = reader.catalog(team, null);
        var snapshot = external.read(Set.of(team), Set.of(), Set.of(), Set.of());
        if (own.program() == null) return new OperationCatalog(team, snapshot.teamName(team), false,
                null, SelfAwardMode.STAFF_APPROVAL, null, List.of());
        return mapper.operationCatalog(own.program(), team, snapshot.teamName(team),
                own.program().isEnabled() && snapshot.teamActive(team),
                own.rule() != null && own.rule().isActive() ? mapper.rule(own.rule()) : null,
                own.rewards().stream().filter(r -> r.isActive()).map(mapper::reward).toList());
    }

    public ProgramSettings settings(Long team) {
        validateTeam(team);
        var own = reader.catalog(team, null);
        if (own.program() == null) return new ProgramSettings(null, team, false, Audience.MEMBERS_AND_ABOVE,
                SelfAwardMode.STAFF_APPROVAL, null, List.of(), List.of());
        return mapper.programSettings(own.program(), team,
                own.rule() == null ? List.of() : List.of(mapper.rule(own.rule())),
                own.rewards().stream().map(mapper::reward).toList());
    }

    public CursorPagedResponse<AwardRequestResponse> myRequests(Long actor, String cursor, int limit) {
        var context = context(LoyaltyCursorCodec.Stream.MY_REQUESTS, actor, null, limit);
        var position = position(context, cursor);
        var now = time.now();
        var own = reader.personal(actor, position == null ? null : position.id(), limit + 1, now);
        return requestsPage(actor, false, false, own, context, limit, now);
    }

    public AwardRequestResponse myRequest(Long actor, UUID request) {
        validate(actor, null); validateRequest(request);
        var now = time.now(); var own = reader.personalRequest(actor, request, now);
        return project(own.rows().getFirst(), actor, false, own, snapshot(own.rows()), now);
    }

    public CursorPagedResponse<AwardRequestResponse> staffRequests(Long actor, Long team, RequestFilter status,
            String cursor, int limit) {
        var filter = status == null ? RequestFilter.PENDING : status;
        boolean pending = filter == RequestFilter.PENDING;
        var context = context(pending ? LoyaltyCursorCodec.Stream.STAFF_PENDING : LoyaltyCursorCodec.Stream.STAFF_ALL,
                actor, team, limit);
        var position = position(context, cursor);
        var now = time.now();
        var own = reader.staff(team, pending, position, pending ? 2 * limit + 1 : limit + 1, now);
        return requestsPage(actor, true, pending, own, context, limit, now);
    }

    public AwardRequestResponse staffRequest(Long actor, Long team, UUID request) {
        validateTeam(team);
        validate(actor, team); validateRequest(request);
        var now = time.now(); var own = reader.staffRequest(team, request, now);
        return project(own.rows().getFirst(), actor, true, own, snapshot(own.rows()), now);
    }

    public int balance(Long team, Long user) {
        validateTeam(team);
        validate(user, team);
        return reader.balance(team, user);
    }

    /** 候補を追加取得せず、出力上限か検査上限で打ち切った位置を署名する。 */
    private CursorPagedResponse<AwardRequestResponse> requestsPage(Long actor, boolean staff, boolean pending,
            LoyaltyQueryReader.Requests own, LoyaltyCursorCodec.Context context, int limit, LocalDateTime now) {
        var snapshot = snapshot(own.rows());
        var data = new ArrayList<AwardRequestResponse>();
        int inspected = 0;
        int inspectionLimit = pending ? 2 * limit : limit;
        LoyaltyAwardRequestEntity last = null;
        while (inspected < own.rows().size() && inspected < inspectionLimit && data.size() < limit) {
            last = own.rows().get(inspected++);
            var response = project(last, actor, staff, own, snapshot, now);
            if (!pending || response.status() == RequestStatus.PENDING) data.add(response);
        }
        boolean hasNext = inspected < own.rows().size();
        var position = last == null ? null : new LoyaltyCursorCodec.Position(last.getId(), pending ? last.getExpiresAt() : null);
        return page(List.copyOf(data), hasNext, limit, context, position);
    }

    private LoyaltyQueryExternalStates.Snapshot snapshot(Collection<LoyaltyAwardRequestEntity> rows) {
        return external.read(rows.stream().map(LoyaltyAwardRequestEntity::getTeamId).collect(Collectors.toSet()),
                rows.stream().map(LoyaltyAwardRequestEntity::getUserId).collect(Collectors.toSet()),
                rows.stream().map(LoyaltyAwardRequestEntity::getSourceLocationId).collect(Collectors.toSet()),
                rows.stream().map(r -> new TeamUserKey(r.getTeamId(), r.getUserId())).collect(Collectors.toSet()));
    }

    /** 保存終端はそのまま、PENDINGだけ現在snapshotで投影しEntityへ書き戻さない。 */
    private AwardRequestResponse project(LoyaltyAwardRequestEntity row, Long actor, boolean staff,
            LoyaltyQueryReader.Requests own, LoyaltyQueryExternalStates.Snapshot snapshot, LocalDateTime now) {
        var program = own.programs().get(row.getTeamId());
        var location = snapshot.locations().get(row.getSourceLocationId());
        boolean locationActive = location != null && location.active() && row.getTeamId().equals(location.teamId());
        RequestReason failure = null;
        if (!snapshot.teamActive(row.getTeamId())) failure = RequestReason.TEAM_INACTIVE;
        else if (!locationActive) failure = RequestReason.LOCATION_INACTIVE;
        else if (!snapshot.userActive(row.getUserId())) failure = RequestReason.USER_INACTIVE;
        else if (!eligible(program, snapshot, row.getTeamId(), row.getUserId())) failure = RequestReason.INELIGIBLE;
        var state = states.evaluate(row, program, own.rules().get(row.getTeamId()), failure,
                own.awarded().contains(new LoyaltyQueryReader.DailyKey(row.getTeamId(), row.getUserId(), row.getRuleId(), row.getAwardOn())),
                own.balances().getOrDefault(new LoyaltyQueryReader.AccountKey(row.getTeamId(), row.getUserId()), 0), now);
        boolean pending = state.status() == RequestStatus.PENDING;
        return mapper.awardRequest(row, snapshot.teamName(row.getTeamId()), snapshot.userName(row.getUserId()),
                locationActive ? snapshot.locationName(row.getSourceLocationId()) : snapshot.closedLocationName(), state.status(), state.reason(),
                staff && !actor.equals(row.getUserId()) && pending, actor.equals(row.getUserId()) && pending);
    }

    private boolean eligible(LoyaltyProgramEntity program, LoyaltyQueryExternalStates.Snapshot snapshot, Long team, Long user) {
        return program != null && eligibility.isEligible(program.getTargetAudience(), snapshot.userActive(user),
                snapshot.member(team, user), snapshot.regular(team, user));
    }

    private void requireReadable(boolean existing, LoyaltyQueryExternalStates.Snapshot snapshot, Long team, Long actor) {
        if (!existing && !(snapshot.teamActive(team) && snapshot.userActive(actor) && snapshot.member(team, actor))) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_001);
        }
    }

    private LoyaltyCursorCodec.Context context(LoyaltyCursorCodec.Stream stream, Long actor, Long team, int limit) {
        if (stream != LoyaltyCursorCodec.Stream.ACCOUNTS && stream != LoyaltyCursorCodec.Stream.MY_REQUESTS) validateTeam(team);
        validate(actor, team);
        if (limit < 1 || limit > 100) throw new BusinessException(CommonErrorCode.COMMON_001);
        return new LoyaltyCursorCodec.Context(stream, actor, team);
    }
    private LoyaltyCursorCodec.Position position(LoyaltyCursorCodec.Context context, String cursor) {
        return cursor == null ? null : cursors.decode(context, cursor);
    }
    private <T> CursorPagedResponse<T> page(List<T> data, boolean hasNext, int limit,
            LoyaltyCursorCodec.Context context, LoyaltyCursorCodec.Position position) {
        return CursorPagedResponse.of(data, new CursorMeta(hasNext ? cursors.encode(context, position) : null, hasNext, limit));
    }
    private void validate(Long actor, Long team) {
        if (actor == null || actor <= 0 || team != null && team <= 0) throw new BusinessException(CommonErrorCode.COMMON_001);
    }
    private void validateTeam(Long team) {
        if (team == null || team <= 0) throw new BusinessException(CommonErrorCode.COMMON_001);
    }
    private void validateRequest(UUID request) {
        if (request == null) throw new BusinessException(CommonErrorCode.COMMON_001);
    }
}
