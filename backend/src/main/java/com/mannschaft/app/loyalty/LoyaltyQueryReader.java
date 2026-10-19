package com.mannschaft.app.loyalty;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 自ドメインだけを同じ読取トランザクションで取得する。外部照会は終了後に行う。 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoyaltyQueryReader {
    private final LoyaltyStore store;

    public List<LoyaltyAccountEntity> accounts(Long actor, UUID after, int size) {
        return after == null ? store.getAccounts().firstPage(actor, PageRequest.of(0, size))
                : store.getAccounts().nextPage(actor, after, PageRequest.of(0, size));
    }

    public Catalog catalog(Long team, Long user) {
        return new Catalog(store.getPrograms().findByTeamId(team).orElse(null),
                store.getRules().findByTeamId(team).orElse(null),
                store.getRewards().findByTeamIdOrderById(team),
                user == null ? null : store.getAccounts().findByTeamIdAndUserId(team, user).orElse(null));
    }

    public List<LoyaltyTransactionEntity> transactions(Long actor, Long team, UUID before, int size) {
        return before == null ? store.getTransactions().firstPage(team, actor, PageRequest.of(0, size))
                : store.getTransactions().nextPage(team, actor, before, PageRequest.of(0, size));
    }

    public Requests personal(Long actor, UUID before, int size, Instant now) {
        var rows = before == null ? store.getRequests().myFirstPage(actor, PageRequest.of(0, size))
                : store.getRequests().myNextPage(actor, before, PageRequest.of(0, size));
        return requests(rows, now);
    }

    public Requests staff(Long team, boolean pending, LoyaltyCursorCodec.Position after, int size,
            Instant now) {
        if (!pending) {
            return requests(after == null ? store.getRequests().teamFirstPage(team, PageRequest.of(0, size))
                    : store.getRequests().teamNextPage(team, after.id(), PageRequest.of(0, size)), now);
        }
        var program = store.getPrograms().findByTeamId(team).orElse(null);
        var rule = store.getRules().findByTeamId(team).orElse(null);
        if (program == null || rule == null || !program.isEnabled() || !rule.isActive()
                || !rule.isSelfAwardEnabled() || program.getSelfAwardMode() != SelfAwardMode.STAFF_APPROVAL) {
            return requests(List.of(), now);
        }
        var rows = after == null
                ? store.getRequests().pendingFirstPage(team, program.getVersion(), rule.getVersion(),
                        LocalDate.ofInstant(now, SERVER_ZONE), now, PageRequest.of(0, size))
                : store.getRequests().pendingNextPage(team, program.getVersion(), rule.getVersion(),
                        LocalDate.ofInstant(now, SERVER_ZONE), now, after.expiresAt(), after.id(), PageRequest.of(0, size));
        return requests(rows, now);
    }

    public Requests personalRequest(Long actor, UUID id, Instant now) {
        return requests(List.of(store.getRequests().findByIdAndUserId(id, actor).orElseThrow(
                () -> new BusinessException(LoyaltyErrorCode.LOYALTY_001))), now);
    }

    public Requests staffRequest(Long team, UUID id, Instant now) {
        return requests(List.of(store.getRequests().findByIdAndTeamId(id, team).orElseThrow(
                () -> new BusinessException(LoyaltyErrorCode.LOYALTY_001))), now);
    }

    public LoyaltyAccountEntity account(Long team, Long user) {
        return store.getAccounts().findByTeamIdAndUserId(team, user).orElse(null);
    }

    public int balance(Long team, Long user) {
        return store.getAccounts().findByTeamIdAndUserId(team, user).map(LoyaltyAccountEntity::getBalance).orElse(0);
    }

    /** 再送照合はQRの解析や現在の資格確認をせず、成功保存行だけを読む。 */
    public LoyaltyTransactionEntity transactionReceipt(Long team, Long actor, RequestOperation operation, UUID key) {
        return store.getTransactions().findByTeamIdAndActorUserIdAndRequestOperationAndRequestKey(team, actor, operation, key)
                .orElse(null);
    }

    public LoyaltyAwardRequestEntity submissionReceipt(Long team, Long actor, UUID key) {
        return store.getRequests().findByTeamIdAndUserIdAndSubmitKey(team, actor, key).orElse(null);
    }

    public LoyaltyAwardRequestEntity actionReceipt(Long team, Long actor, RequestOperation operation, UUID key) {
        return store.getRequests().findByTeamIdAndTerminalActorUserIdAndTerminalActionAndActionKey(team, actor, operation, key)
                .orElse(null);
    }

    public LoyaltyAwardRequestEntity personalRequestRow(Long actor, UUID id) {
        return store.getRequests().findByIdAndUserId(id, actor)
                .orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_001));
    }

    public LoyaltyAwardRequestEntity teamRequestRow(Long team, UUID id) {
        return store.getRequests().findByIdAndTeamId(id, team)
                .orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_001));
    }

    public LoyaltyAwardRequestEntity pendingSnapshot(Long team, Long user, UUID rule, java.time.LocalDate day) {
        return store.getRequests().findPendingSnapshot(team, user, rule, day).orElse(null);
    }

    public boolean awardedToday(Long team, Long user, UUID rule, java.time.LocalDate day) {
        return store.getTransactions().existsByTeamIdAndUserIdAndRuleIdAndAwardOn(team, user, rule, day);
    }

    private Requests requests(List<LoyaltyAwardRequestEntity> rows, Instant now) {
        if (rows.isEmpty()) return new Requests(List.of(), Map.of(), Map.of(), Map.of(), Set.of());
        var teams = rows.stream().map(LoyaltyAwardRequestEntity::getTeamId).collect(Collectors.toSet());
        var users = rows.stream().map(LoyaltyAwardRequestEntity::getUserId).collect(Collectors.toSet());
        var programs = store.getPrograms().findByTeamIdIn(teams).stream().collect(Collectors.toMap(
                LoyaltyProgramEntity::getTeamId, Function.identity()));
        var rules = store.getRules().findByTeamIdIn(teams).stream().collect(Collectors.toMap(
                LoyaltyRuleEntity::getTeamId, Function.identity()));
        var balances = store.getAccounts().findByTeamIdInAndUserIdIn(teams, users).stream().collect(Collectors.toMap(
                a -> new AccountKey(a.getTeamId(), a.getUserId()), LoyaltyAccountEntity::getBalance));
        var awarded = store.getTransactions().findByTeamIdInAndUserIdInAndAwardOn(teams, users, LocalDate.ofInstant(now, SERVER_ZONE))
                .stream().filter(t -> t.getRuleId() != null).map(t -> new DailyKey(t.getTeamId(), t.getUserId(),
                        t.getRuleId(), t.getAwardOn())).collect(Collectors.toSet());
        return new Requests(List.copyOf(rows), programs, rules, balances, awarded);
    }

    public record Catalog(LoyaltyProgramEntity program, LoyaltyRuleEntity rule,
            List<LoyaltyRewardEntity> rewards, LoyaltyAccountEntity account) { }
    public record AccountKey(Long team, Long user) { }
    public record DailyKey(Long team, Long user, UUID rule, java.time.LocalDate day) { }
    public record Requests(List<LoyaltyAwardRequestEntity> rows, Map<Long, LoyaltyProgramEntity> programs,
            Map<Long, LoyaltyRuleEntity> rules, Map<AccountKey, Integer> balances, Set<DailyKey> awarded) { }
}
