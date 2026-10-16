package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * program共有→rule/reward→request(id順)→account排他の順に自表だけを更新する。
 * READ_COMMITTEDで空候補のgapを保持せず、DB一意制約と排他行で不変条件を担保する。
 * 既知の一意競合/deadlockだけを失敗TX終了後にreceipt照合から再試行する。
 */
@Service
public class LoyaltyPointsWriter {
    private static final Set<String> RETRY_UNIQUES = Set.of("uk_tla_owner", "uk_tlt_receipt", "uk_tlt_daily",
            "uk_tlar_submit", "uk_tlar_pending", "uk_tlar_code", "uk_tlar_action");
    private static final int MAX_ATTEMPTS = 24;
    private static final char[] CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final LoyaltyStore store;
    private final LoyaltyTime time;
    private final LoyaltyMapper mapper;
    private final LoyaltyRequestStatePolicy states;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public LoyaltyPointsWriter(LoyaltyStore store, LoyaltyTime time, LoyaltyMapper mapper,
            LoyaltyRequestStatePolicy states, ApplicationEventPublisher events, PlatformTransactionManager manager) {
        this.store = store; this.time = time; this.mapper = mapper; this.states = states; this.events = events;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public TransactionResponse award(LoyaltyWriteCommand command) {
        return execute(() -> awardInTransaction(command));
    }

    public TransactionResponse redeem(LoyaltyWriteCommand command) {
        return execute(() -> {
            var receipt = transactionReceipt(command);
            if (receipt != null) return mapper.transaction(receipt);
            var program = program(command.teamId());
            var reward = store.getRewards().lockShared(command.teamId(), command.itemId())
                    .orElseThrow(() -> error(LoyaltyErrorCode.LOYALTY_001));
            checkProgram(program, command.expectedProgramVersion());
            if (reward.getVersion() != command.expectedItemVersion()) throw error(LoyaltyErrorCode.LOYALTY_007);
            if (!reward.isActive()) throw error(LoyaltyErrorCode.LOYALTY_004);
            var account = store.getAccounts().lockExclusive(command.teamId(), command.userId())
                    .orElseThrow(() -> error(LoyaltyErrorCode.LOYALTY_008));
            receipt = transactionReceipt(command);
            if (receipt != null) return mapper.transaction(receipt);
            LocalDateTime now = time.now();
            account.apply(-reward.getRequiredPoints(), now);
            var row = transaction(command, account, program, TransactionKind.REDEEM, TransactionChannel.STAFF,
                    -reward.getRequiredPoints(), null, reward.getId(), null, reward.getName(), reward.getVersion(), now);
            store.getTransactions().saveAndFlush(row);
            publish(command.teamId(), command.actorUserId(), command.userId(), command.operation(), row.getId(), null);
            return mapper.transaction(row);
        });
    }

    public LoyaltySubmissionOutcome submit(LoyaltyWriteCommand command) {
        return execute(() -> {
            var receipt = submissionReceipt(command);
            if (receipt != null) return receipt;
            var program = program(command.teamId());
            // mode分岐より先にAUTO台帳と申請両方を再照合する。
            receipt = submissionReceipt(command);
            if (receipt != null) return receipt;
            var rule = rule(command.teamId(), command.itemId());
            checkProgram(program, command.expectedProgramVersion());
            checkRule(rule, command.expectedItemVersion());
            if (!command.actorUserId().equals(command.userId()) || !rule.isSelfAwardEnabled()
                    || rule.getFrequency() != Frequency.ONCE_PER_JST_DAY) throw error(LoyaltyErrorCode.LOYALTY_004);
            if (program.getSelfAwardMode() == SelfAwardMode.AUTO) {
                return new LoyaltySubmissionOutcome(awardLocked(command, program, rule), null);
            }
            LocalDateTime now = time.now();
            var pending = store.getRequests().lockPending(command.teamId(), command.userId(), rule.getId(), now.toLocalDate());
            var snapshot = command.pendingSnapshot();
            UUID currentPending = pending.isEmpty() ? null : pending.getFirst().getId();
            if (snapshot != null && !java.util.Objects.equals(snapshot.requestId(), currentPending)) {
                throw new LoyaltyPendingSnapshotChangedException();
            }
            var account = store.getAccounts().lockExclusive(command.teamId(), command.userId()).orElse(null);
            receipt = submissionReceipt(command);
            if (receipt != null) return receipt;
            boolean awarded = daily(command.teamId(), command.userId(), rule.getId(), now.toLocalDate());
            for (var row : pending) {
                var state = states.evaluate(row, program, rule, snapshot == null ? null : snapshot.externalFailure(),
                        awarded, account == null ? 0 : account.getBalance(), now);
                if (state.status() == RequestStatus.PENDING) throw error(LoyaltyErrorCode.LOYALTY_013);
                closeAutomatically(row, state, now);
            }
            // HibernateはINSERTをUPDATEより先に流すため、旧PENDINGのunique枠を同TX内で先に解放する。
            if (!pending.isEmpty()) store.getRequests().flush();
            if (awarded) throw error(LoyaltyErrorCode.LOYALTY_005);
            var row = LoyaltyAwardRequestEntity.builder().teamId(command.teamId()).userId(command.userId())
                    .ruleId(rule.getId()).sourceLocationId(command.sourceLocationId()).programVersion(program.getVersion())
                    .ruleVersion(rule.getVersion()).points(rule.getPoints()).ruleName(rule.getName())
                    .locationName(command.locationName()).awardOn(now.toLocalDate()).expiresAt(time.expiresAt(now))
                    .status(RequestStatus.PENDING).version(0).verificationCode(verificationCode())
                    .submitKey(command.requestKey()).submitPayloadHash(command.payloadHash().clone())
                    .createdAt(now).updatedAt(now).build();
            store.getRequests().saveAndFlush(row);
            publish(command.teamId(), command.actorUserId(), command.userId(), RequestOperation.SELF_SUBMIT, null, row.getId());
            return new LoyaltySubmissionOutcome(null, row);
        });
    }

    public LoyaltyRequestActionOutcome act(LoyaltyRequestActionCommand command) {
        return execute(() -> {
            var receipt = actionReceipt(command);
            if (receipt != null) return receipt;
            UUID ruleId = store.getRequests().findRuleId(command.teamId(), command.requestId())
                    .orElseThrow(() -> error(LoyaltyErrorCode.LOYALTY_001));
            var program = store.getPrograms().lockShared(command.teamId()).orElse(null);
            var rule = store.getRules().lockShared(command.teamId(), ruleId).orElse(null);
            var row = store.getRequests().lockExclusive(command.teamId(), command.requestId())
                    .orElseThrow(() -> error(LoyaltyErrorCode.LOYALTY_001));
            receipt = actionReceipt(command);
            if (receipt != null) return receipt;
            if (command.operation() == RequestOperation.CANCEL && !row.getUserId().equals(command.actorUserId())) {
                throw error(LoyaltyErrorCode.LOYALTY_001);
            }
            if (command.operation() == RequestOperation.APPROVE && row.getUserId().equals(command.actorUserId())) {
                throw error(LoyaltyErrorCode.LOYALTY_015);
            }
            if (row.getStatus() != RequestStatus.PENDING) return terminal(row);
            if (row.getVersion() != command.expectedRequestVersion()) throw error(LoyaltyErrorCode.LOYALTY_007);
            if (command.operation() == RequestOperation.APPROVE
                    && !row.getVerificationCode().equals(command.verificationCode())) throw error(LoyaltyErrorCode.LOYALTY_007);
            if (command.operation() != RequestOperation.APPROVE && command.operation() != RequestOperation.CANCEL
                    && command.operation() != RequestOperation.REJECT) throw new BusinessException(CommonErrorCode.COMMON_001);
            var account = store.getAccounts().lockExclusive(command.teamId(), row.getUserId()).orElse(null);
            LocalDateTime now = time.now();
            var state = states.evaluate(row, program, rule, command.externalFailure(),
                    daily(command.teamId(), row.getUserId(), ruleId, row.getAwardOn()), account == null ? 0 : account.getBalance(), now);
            if (state.status() != RequestStatus.PENDING) {
                closeAutomatically(row, state, now);
                store.getRequests().flush();
                publish(command.teamId(), command.actorUserId(), row.getUserId(), command.operation(), null, row.getId());
                return terminal(row); // commit後、Facadeが409へ変換する。
            }
            if (command.operation() == RequestOperation.APPROVE) {
                if (account == null) account = createAccount(command.teamId(), row.getUserId(), now);
                account.apply(row.getPoints(), now);
                var write = new LoyaltyWriteCommand(command.teamId(), command.actorUserId(), row.getUserId(), ruleId,
                        row.getRuleVersion(), row.getProgramVersion(), RequestOperation.APPROVE,
                        command.requestKey(), command.payloadHash(), row.getSourceLocationId(), row.getLocationName());
                var ledger = transaction(write, account, program, TransactionKind.AWARD, TransactionChannel.SELF_APPROVED,
                        row.getPoints(), ruleId, null, row.getAwardOn(), row.getRuleName(), row.getRuleVersion(), now);
                store.getTransactions().saveAndFlush(ledger);
                row.close(RequestStatus.APPROVED, null, command.actorUserId(), RequestOperation.APPROVE,
                        command.requestKey(), command.payloadHash(), ledger.getId(), now);
                store.getRequests().flush();
                publish(command.teamId(), command.actorUserId(), row.getUserId(), RequestOperation.APPROVE, ledger.getId(), row.getId());
                return new LoyaltyRequestActionOutcome(row, mapper.transaction(ledger), null);
            }
            boolean cancel = command.operation() == RequestOperation.CANCEL;
            row.close(cancel ? RequestStatus.CANCELLED : RequestStatus.REJECTED,
                    cancel ? RequestReason.USER_CANCELLED : RequestReason.STAFF_REJECTED,
                    command.actorUserId(), command.operation(), command.requestKey(), command.payloadHash(), null, now);
            store.getRequests().flush();
            publish(command.teamId(), command.actorUserId(), row.getUserId(), command.operation(), null, row.getId());
            return new LoyaltyRequestActionOutcome(row, null, null);
        });
    }

    private TransactionResponse awardInTransaction(LoyaltyWriteCommand command) {
        var receipt = transactionReceipt(command);
        if (receipt != null) return mapper.transaction(receipt);
        var program = program(command.teamId());
        var rule = rule(command.teamId(), command.itemId());
        checkProgram(program, command.expectedProgramVersion());
        checkRule(rule, command.expectedItemVersion());
        return awardLocked(command, program, rule);
    }

    private TransactionResponse awardLocked(LoyaltyWriteCommand command, LoyaltyProgramEntity program, LoyaltyRuleEntity rule) {
        LocalDateTime now = time.now();
        LocalDate day = rule.getFrequency() == Frequency.ONCE_PER_JST_DAY ? now.toLocalDate() : null;
        List<LoyaltyAwardRequestEntity> pending = day == null ? List.of()
                : store.getRequests().lockPending(command.teamId(), command.userId(), rule.getId(), day);
        var account = store.getAccounts().lockExclusive(command.teamId(), command.userId())
                .orElseGet(() -> createAccount(command.teamId(), command.userId(), now));
        var receipt = transactionReceipt(command);
        if (receipt != null) return mapper.transaction(receipt);
        if (day != null && daily(command.teamId(), command.userId(), rule.getId(), day)) throw error(LoyaltyErrorCode.LOYALTY_005);
        account.apply(rule.getPoints(), now);
        TransactionChannel channel = command.operation() == RequestOperation.SELF_SUBMIT
                ? TransactionChannel.SELF_AUTO : TransactionChannel.STAFF;
        var ledger = transaction(command, account, program, TransactionKind.AWARD, channel, rule.getPoints(),
                rule.getId(), null, day, rule.getName(), rule.getVersion(), now);
        store.getTransactions().saveAndFlush(ledger);
        // §5手順7固有: 仮投影に関わらず保存PENDINGだけを閉じ、保存終端は触らない。
        for (var row : pending) row.close(RequestStatus.SUPERSEDED, RequestReason.DAILY_AWARDED,
                null, null, null, null, null, now);
        store.getRequests().flush();
        publish(command.teamId(), command.actorUserId(), command.userId(), command.operation(), ledger.getId(), null);
        return mapper.transaction(ledger);
    }

    private LoyaltyProgramEntity program(Long team) {
        return store.getPrograms().lockShared(team).orElseThrow(() -> error(LoyaltyErrorCode.LOYALTY_004));
    }
    private LoyaltyRuleEntity rule(Long team, UUID id) {
        return store.getRules().lockShared(team, id).orElseThrow(() -> error(LoyaltyErrorCode.LOYALTY_001));
    }
    private void checkProgram(LoyaltyProgramEntity program, long expected) {
        if (program.getVersion() != expected) throw error(LoyaltyErrorCode.LOYALTY_007);
        if (!program.isEnabled()) throw error(LoyaltyErrorCode.LOYALTY_004);
    }
    private void checkRule(LoyaltyRuleEntity rule, long expected) {
        if (rule.getVersion() != expected) throw error(LoyaltyErrorCode.LOYALTY_007);
        if (!rule.isActive()) throw error(LoyaltyErrorCode.LOYALTY_004);
    }
    private boolean daily(Long team, Long user, UUID rule, LocalDate day) {
        return store.getTransactions().existsByTeamIdAndUserIdAndRuleIdAndAwardOn(team, user, rule, day);
    }
    private LoyaltyAccountEntity createAccount(Long team, Long user, LocalDateTime now) {
        return store.getAccounts().saveAndFlush(LoyaltyAccountEntity.builder().teamId(team).userId(user).balance(0)
                .lifetimeEarned(0).createdAt(now).updatedAt(now).build());
    }
    private LoyaltyTransactionEntity transaction(LoyaltyWriteCommand c, LoyaltyAccountEntity a,
            LoyaltyProgramEntity p, TransactionKind kind, TransactionChannel channel, int delta,
            UUID rule, UUID reward, LocalDate day, String label, long version, LocalDateTime now) {
        return LoyaltyTransactionEntity.builder().teamId(c.teamId()).accountId(a.getId()).userId(c.userId())
                .actorUserId(c.actorUserId()).kind(kind).channel(channel).requestOperation(c.operation()).delta(delta)
                .balanceAfter(a.getBalance()).ruleId(rule).rewardId(reward).sourceLocationId(c.sourceLocationId())
                .awardOn(day).label(label).ruleOrRewardVersion(version).programVersion(p.getVersion())
                .requestKey(c.requestKey()).payloadHash(c.payloadHash().clone()).createdAt(now).updatedAt(now).build();
    }
    private LoyaltyTransactionEntity transactionReceipt(LoyaltyWriteCommand command) {
        var row = store.getTransactions().findByTeamIdAndActorUserIdAndRequestOperationAndRequestKey(
                command.teamId(), command.actorUserId(), command.operation(), command.requestKey()).orElse(null);
        if (row != null) checkHash(row.getPayloadHash(), command.payloadHash());
        return row;
    }
    private LoyaltySubmissionOutcome submissionReceipt(LoyaltyWriteCommand command) {
        var ledger = transactionReceipt(command);
        if (ledger != null) return new LoyaltySubmissionOutcome(mapper.transaction(ledger), null);
        var row = store.getRequests().findByTeamIdAndUserIdAndSubmitKey(command.teamId(), command.actorUserId(), command.requestKey()).orElse(null);
        if (row == null) return null;
        checkHash(row.getSubmitPayloadHash(), command.payloadHash());
        return new LoyaltySubmissionOutcome(null, row);
    }
    private LoyaltyRequestActionOutcome actionReceipt(LoyaltyRequestActionCommand c) {
        if (c.operation() == RequestOperation.APPROVE) {
            var ledger = store.getTransactions().findByTeamIdAndActorUserIdAndRequestOperationAndRequestKey(
                    c.teamId(), c.actorUserId(), c.operation(), c.requestKey()).orElse(null);
            if (ledger != null) {
                checkHash(ledger.getPayloadHash(), c.payloadHash());
                var request = store.getRequests().findByTeamIdAndTransactionId(c.teamId(), ledger.getId())
                        .orElseThrow(() -> error(LoyaltyErrorCode.LOYALTY_001));
                if (!request.getId().equals(c.requestId())) throw error(LoyaltyErrorCode.LOYALTY_006);
                return new LoyaltyRequestActionOutcome(request, mapper.transaction(ledger), null);
            }
        }
        var row = store.getRequests().findByTeamIdAndTerminalActorUserIdAndTerminalActionAndActionKey(
                c.teamId(), c.actorUserId(), c.operation(), c.requestKey()).orElse(null);
        if (row == null) return null;
        if (!row.getId().equals(c.requestId())) throw error(LoyaltyErrorCode.LOYALTY_006);
        checkHash(row.getActionPayloadHash(), c.payloadHash());
        return new LoyaltyRequestActionOutcome(row, null, null);
    }
    private void checkHash(byte[] stored, byte[] incoming) {
        if (stored == null || incoming == null || !MessageDigest.isEqual(stored, incoming)) throw error(LoyaltyErrorCode.LOYALTY_006);
    }
    private LoyaltyRequestActionOutcome terminal(LoyaltyAwardRequestEntity row) {
        boolean invalid = row.getStatus() == RequestStatus.EXPIRED || row.getStatus() == RequestStatus.INVALIDATED
                || row.getStatus() == RequestStatus.SUPERSEDED;
        return new LoyaltyRequestActionOutcome(row, null, invalid ? LoyaltyErrorCode.LOYALTY_014 : LoyaltyErrorCode.LOYALTY_013);
    }
    private void closeAutomatically(LoyaltyAwardRequestEntity row, LoyaltyEffectiveRequestState state, LocalDateTime now) {
        row.close(state.status(), state.reason(), null, null, null, null, null, now);
    }
    private String verificationCode() {
        char[] result = new char[8];
        for (int index = 0; index < result.length; index++) result[index] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        return new String(result);
    }
    private void publish(Long team, Long actor, Long target, RequestOperation operation, UUID ledger, UUID request) {
        events.publishEvent(new LoyaltyCommittedEvent(team, actor, target, operation, ledger, request));
    }
    private BusinessException error(LoyaltyErrorCode code) { return new BusinessException(code); }

    private <T> T execute(Supplier<T> work) {
        boolean joined = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 1; ; attempt++) {
            try { return transaction.execute(status -> work.get()); }
            catch (RuntimeException failure) {
                if (joined || attempt >= MAX_ATTEMPTS || !retryable(failure)) throw failure;
            }
        }
    }
    private boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if (name != null) {
                    name = name.replace("`", "").replace("'", "");
                    int separator = name.lastIndexOf('.');
                    if (RETRY_UNIQUES.contains(name.substring(separator + 1))) return true;
                }
            }
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205
                    || "40001".equals(sql.getSQLState()))) return true;
        }
        return false;
    }
}
