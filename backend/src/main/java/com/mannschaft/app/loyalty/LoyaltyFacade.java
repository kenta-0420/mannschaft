package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.loyalty.dto.AccountDetail;
import com.mannschaft.app.loyalty.dto.AccountSummary;
import com.mannschaft.app.loyalty.dto.ApproveRequest;
import com.mannschaft.app.loyalty.dto.AwardRequestResponse;
import com.mannschaft.app.loyalty.dto.CreateRewardRequest;
import com.mannschaft.app.loyalty.dto.CreateRuleRequest;
import com.mannschaft.app.loyalty.dto.OperationCatalog;
import com.mannschaft.app.loyalty.dto.ProgramSettings;
import com.mannschaft.app.loyalty.dto.RedeemRequest;
import com.mannschaft.app.loyalty.dto.RedemptionPreview;
import com.mannschaft.app.loyalty.dto.RedemptionPreviewRequest;
import com.mannschaft.app.loyalty.dto.RequestActionRequest;
import com.mannschaft.app.loyalty.dto.RewardResponse;
import com.mannschaft.app.loyalty.dto.RuleResponse;
import com.mannschaft.app.loyalty.dto.ScanPreview;
import com.mannschaft.app.loyalty.dto.ScanPreviewRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardBootstrap;
import com.mannschaft.app.loyalty.dto.SelfAwardBootstrapRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardPreview;
import com.mannschaft.app.loyalty.dto.SelfAwardPreviewRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardResult;
import com.mannschaft.app.loyalty.dto.StaffAwardRequest;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.dto.UpdateProgramRequest;
import com.mannschaft.app.loyalty.dto.UpdateRewardRequest;
import com.mannschaft.app.loyalty.dto.UpdateRuleRequest;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Supplier;
import java.security.MessageDigest;
import com.mannschaft.app.common.AccessControlService.TeamUserKey;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import com.mannschaft.app.membership.service.MemberCardService;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.membership.service.CheckinLocationService;

/**
 * TXを持たない操作入口。現在の認可、成功receipt、新規検証、独立したWriter TXの順に接続する。
 */
@Service
@RequiredArgsConstructor
public class LoyaltyFacade {
    private final LoyaltyAccessPolicy policy;
    private final LoyaltyQueryService query;
    private final LoyaltySettingsWriter settingsWriter;
    private final CheckinLocationService locationService;
    private final MemberCardService memberCardService;
    private final LoyaltyQueryReader reader;
    private final LoyaltyQueryExternalStates external;
    private final LoyaltyEligibilityPolicy eligibility;
    private final LoyaltyPointsWriter writer;
    private final LoyaltyPayloadHasher hasher;
    private final LoyaltyMapper mapper;
    private final LoyaltyTime time;
    private final LoyaltyRateLimits rateLimits;
    /** Java UUIDパーサーが許す短縮形を排除し、生headerを例外へ含めない。 */
    static UUID idempotencyKey(String raw) {
        if (raw == null || !raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return UUID.fromString(raw);
    }

    public CursorPagedResponse<AccountSummary> listAccounts(Long actor, String cursor, int limit) {
        policy.requireActiveActor(actor); return query.listAccounts(actor, cursor, limit);
    }
    public AccountDetail account(Long actor, Long team) {
        policy.requireActiveActor(actor); return query.account(actor, team);
    }
    public CursorPagedResponse<TransactionResponse> transactions(Long actor, Long team, String cursor, int limit) {
        policy.requireActiveActor(actor); return query.transactions(actor, team, cursor, limit);
    }
    public OperationCatalog operationCatalog(Long actor, Long team) {
        operator(actor, team); policy.requireActiveTeam(team); return query.operationCatalog(team);
    }
    public ProgramSettings settings(Long actor, Long team) {
        settingsAdmin(actor, team); return query.settings(team);
    }
    public ProgramSettings updateSettings(Long actor, Long team, UpdateProgramRequest body) {
        settingsAdmin(actor, team); return settingsWriter.updateProgram(team, body);
    }
    public RuleResponse createRule(Long actor, Long team, CreateRuleRequest body) {
        settingsAdmin(actor, team); return settingsWriter.createRule(team, body);
    }
    public RuleResponse updateRule(Long actor, Long team, UUID rule, UpdateRuleRequest body) {
        settingsAdmin(actor, team); return settingsWriter.updateRule(team, rule, body);
    }
    public RewardResponse createReward(Long actor, Long team, CreateRewardRequest body) {
        settingsAdmin(actor, team); return settingsWriter.createReward(team, body);
    }
    public RewardResponse updateReward(Long actor, Long team, UUID reward, UpdateRewardRequest body) {
        settingsAdmin(actor, team); return settingsWriter.updateReward(team, reward, body);
    }
    public ScanPreview scanPreview(Long actor, Long team, ScanPreviewRequest body) {
        operator(actor, team); rateLimits.preview(actor, team);
        Long user = verifiedUser(body.qrToken());
        var own = reader.catalog(team, user);
        var state = current(team, user, Set.of());
        requireCurrent(team, user, state);
        var program = program(own);
        var rule = rule(own, body.ruleId(), false);
        return mapper.scanPreview(state.userName(user), balance(own), program, mapper.rule(rule), eligible(program, team, user, state));
    }
    public RedemptionPreview redemptionPreview(Long actor, Long team, RedemptionPreviewRequest body) {
        operator(actor, team); rateLimits.preview(actor, team);
        Long user = verifiedUser(body.qrToken());
        var own = reader.catalog(team, user);
        var state = current(team, user, Set.of());
        requireCurrent(team, user, state);
        var program = program(own);
        var reward = reward(own, body.rewardId());
        return mapper.redemptionPreview(state.userName(user), balance(own), program, mapper.reward(reward), balance(own) >= reward.getRequiredPoints());
    }
    public TransactionResponse award(Long actor, Long team, StaffAwardRequest body, String idempotencyKey) {
        operator(actor, team);
        UUID key = idempotencyKey(idempotencyKey); byte[] hash = hasher.staffAward(team, body);
        return execute(() -> operator(actor, team), () -> transactionReceipt(team, actor, RequestOperation.STAFF_AWARD, key, hash), () -> rateLimits.write(actor, team), () -> {
            Long user = verifiedUser(body.qrToken());
            var own = reader.catalog(team, user); var state = current(team, user, Set.of());
            requireCurrent(team, user, state); var program = program(own); rule(own, body.ruleId(), false);
            requireEligible(program, team, user, state);
            return writer.award(new LoyaltyWriteCommand(team, actor, user, body.ruleId(), body.expectedRuleVersion(),
                    body.expectedProgramVersion(), RequestOperation.STAFF_AWARD, key, hash, null, null));
        });
    }
    public TransactionResponse redeem(Long actor, Long team, RedeemRequest body, String idempotencyKey) {
        operator(actor, team);
        UUID key = idempotencyKey(idempotencyKey); byte[] hash = hasher.redeem(team, body);
        return execute(() -> operator(actor, team), () -> transactionReceipt(team, actor, RequestOperation.REDEEM, key, hash), () -> rateLimits.write(actor, team), () -> {
            Long user = verifiedUser(body.qrToken());
            var own = reader.catalog(team, user); var state = current(team, user, Set.of());
            requireCurrent(team, user, state); program(own); reward(own, body.rewardId());
            // 既存残高の交換には現在の在籍・付与対象資格を要求しない。
            return writer.redeem(new LoyaltyWriteCommand(team, actor, user, body.rewardId(), body.expectedRewardVersion(),
                    body.expectedProgramVersion(), RequestOperation.REDEEM, key, hash, null, null));
        });
    }
    public SelfAwardBootstrap bootstrap(Long actor, SelfAwardBootstrapRequest body) {
        policy.requireActiveActor(actor);
        var location = verifiedLocation(body.locationToken(), null);
        rateLimits.preview(actor, location.teamId());
        var own = reader.catalog(location.teamId(), actor);
        var state = current(location.teamId(), actor, Set.of(location.locationId()));
        requireLocation(location.teamId(), location.locationId(), state);
        requireSelf(location.teamId(), actor, own, own.rule() == null ? null : own.rule().getId(), state);
        return new SelfAwardBootstrap(selfPreview(location.teamId(), actor, own, state), query.verifiedBootstrapAccount(actor, location.teamId()));
    }
    public SelfAwardPreview selfPreview(Long actor, Long team, SelfAwardPreviewRequest body) {
        policy.requireActiveActor(actor); rateLimits.preview(actor, team);
        var location = verifiedLocation(body.locationToken(), team);
        var own = reader.catalog(team, actor); var state = current(team, actor, Set.of(location.locationId()));
        requireLocation(team, location.locationId(), state);
        requireSelf(team, actor, own, body.ruleId(), state);
        return selfPreview(team, actor, own, state);
    }
    public SelfAwardResult selfAward(Long actor, Long team, SelfAwardRequest body, String idempotencyKey) {
        policy.requireActiveActor(actor);
        UUID key = idempotencyKey(idempotencyKey); byte[] hash = hasher.selfSubmit(team, body);
        return execute(() -> policy.requireActiveActor(actor), () -> submissionReceipt(team, actor, key, hash), () -> rateLimits.write(actor, team), () -> {
            var location = verifiedLocation(body.locationToken(), team);
            var own = reader.catalog(team, actor);
            var pending = reader.pendingSnapshot(team, actor, body.ruleId(), time.today());
            var locationIds = new HashSet<Long>(); locationIds.add(location.locationId());
            if (pending != null) locationIds.add(pending.getSourceLocationId());
            var state = current(team, actor, locationIds);
            requireLocation(team, location.locationId(), state);
            requireSelf(team, actor, own, body.ruleId(), state);
            var pendingSnapshot = new LoyaltyWriteCommand.PendingSnapshot(pending == null ? null : pending.getId(),
                    pending == null ? null : externalFailure(pending, own.program(), state));
            var result = writer.submit(new LoyaltyWriteCommand(team, actor, actor, body.ruleId(), body.expectedRuleVersion(),
                    body.expectedProgramVersion(), RequestOperation.SELF_SUBMIT, key, hash, location.locationId(),
                    state.locationName(location.locationId()), pendingSnapshot));
            // 旧PENDINGの終端化を伴うエラーは、Writerの正常return/commit後に応答へ変換する。
            if (result.errorCode() != null) throw new BusinessException(result.errorCode());
            return result.transaction() != null ? new SelfAwardResult(SelfAwardOutcome.AWARDED, result.transaction(), null)
                    : new SelfAwardResult(SelfAwardOutcome.SUBMITTED, null, query.myRequest(actor, result.request().getId()));
        });
    }
    public CursorPagedResponse<AwardRequestResponse> myRequests(Long actor, String cursor, int limit) {
        policy.requireActiveActor(actor); return query.myRequests(actor, cursor, limit);
    }
    public AwardRequestResponse myRequest(Long actor, UUID request) {
        policy.requireActiveActor(actor); return query.myRequest(actor, request);
    }
    public AwardRequestResponse cancel(Long actor, UUID request, RequestActionRequest body, String idempotencyKey) {
        policy.requireActiveActor(actor);
        Long team = reader.personalRequestRow(actor, request).getTeamId();
        UUID key = idempotencyKey(idempotencyKey); byte[] hash = hasher.action(RequestOperation.CANCEL, team, request, body);
        return execute(() -> policy.requireActiveActor(actor), () -> actionReceipt(team, actor, RequestOperation.CANCEL, key, hash, false), () -> rateLimits.write(actor, team), () -> {
            var row = reader.personalRequestRow(actor, request);
            act(actor, team, row, body.expectedRequestVersion(), null, RequestOperation.CANCEL, key, hash);
            return query.myRequest(actor, request);
        });
    }
    public CursorPagedResponse<AwardRequestResponse> staffRequests(Long actor, Long team, RequestFilter status, String cursor, int limit) {
        operator(actor, team); policy.requireActiveTeam(team); return query.staffRequests(actor, team, status, cursor, limit);
    }
    public AwardRequestResponse staffRequest(Long actor, Long team, UUID request) {
        operator(actor, team); policy.requireActiveTeam(team); return query.staffRequest(actor, team, request);
    }
    public TransactionResponse approve(Long actor, Long team, UUID request, ApproveRequest body, String idempotencyKey) {
        operator(actor, team);
        UUID key = idempotencyKey(idempotencyKey); byte[] hash = hasher.approve(team, request, body);
        return execute(() -> operator(actor, team), () -> transactionReceipt(team, actor, RequestOperation.APPROVE, key, hash), () -> rateLimits.write(actor, team), () -> {
            var row = reader.teamRequestRow(team, request);
            if (actor.equals(row.getUserId())) throw new BusinessException(LoyaltyErrorCode.LOYALTY_015);
            return act(actor, team, row, body.expectedRequestVersion(), body.verificationCode(), RequestOperation.APPROVE, key, hash).transaction();
        });
    }
    public AwardRequestResponse reject(Long actor, Long team, UUID request, RequestActionRequest body, String idempotencyKey) {
        operator(actor, team);
        UUID key = idempotencyKey(idempotencyKey); byte[] hash = hasher.action(RequestOperation.REJECT, team, request, body);
        return execute(() -> operator(actor, team), () -> actionReceipt(team, actor, RequestOperation.REJECT, key, hash, true), () -> rateLimits.write(actor, team), () -> {
            act(actor, team, reader.teamRequestRow(team, request), body.expectedRequestVersion(), null, RequestOperation.REJECT, key, hash);
            return query.staffRequest(actor, team, request);
        });
    }

    private void operator(Long actor, Long team) {
        policy.requireActiveActor(actor); policy.requireOperator(actor, team);
    }
    private void settingsAdmin(Long actor, Long team) {
        policy.requireActiveActor(actor); policy.requireSettingsAdmin(actor, team); policy.requireActiveTeam(team);
    }

    private LoyaltyQueryExternalStates.Snapshot current(Long team, Long user, Set<Long> locations) {
        return external.read(Set.of(team), Set.of(user), locations, Set.of(new TeamUserKey(team, user)));
    }

    private void requireCurrent(Long team, Long user, LoyaltyQueryExternalStates.Snapshot state) {
        if (!state.teamActive(team)) throw new BusinessException(LoyaltyErrorCode.LOYALTY_001);
        if (!state.userActive(user)) throw new BusinessException(LoyaltyErrorCode.LOYALTY_003);
    }

    private void requireLocation(Long team, Long id, LoyaltyQueryExternalStates.Snapshot state) {
        var location = state.locations().get(id);
        if (location == null || !location.active() || !team.equals(location.teamId())) throw new BusinessException(LoyaltyErrorCode.LOYALTY_010);
    }

    private LoyaltyProgramEntity program(LoyaltyQueryReader.Catalog own) {
        if (own.program() == null || !own.program().isEnabled()) throw new BusinessException(LoyaltyErrorCode.LOYALTY_004);
        return own.program();
    }

    private LoyaltyRuleEntity rule(LoyaltyQueryReader.Catalog own, UUID id, boolean self) {
        if (own.rule() == null || !own.rule().getId().equals(id)) throw new BusinessException(LoyaltyErrorCode.LOYALTY_001);
        if (!own.rule().isActive() || self && !own.rule().isSelfAwardEnabled()) throw new BusinessException(LoyaltyErrorCode.LOYALTY_004);
        return own.rule();
    }

    private LoyaltyRewardEntity reward(LoyaltyQueryReader.Catalog own, UUID id) {
        var reward = own.rewards().stream().filter(row -> row.getId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_001));
        if (!reward.isActive()) throw new BusinessException(LoyaltyErrorCode.LOYALTY_004);
        return reward;
    }

    private boolean eligible(LoyaltyProgramEntity program, Long team, Long user, LoyaltyQueryExternalStates.Snapshot state) {
        return eligibility.isEligible(program.getTargetAudience(), state.userActive(user), state.member(team, user), state.regular(team, user));
    }

    private void requireEligible(LoyaltyProgramEntity program, Long team, Long user, LoyaltyQueryExternalStates.Snapshot state) {
        if (!eligible(program, team, user, state)) throw new BusinessException(LoyaltyErrorCode.LOYALTY_003);
    }

    private void requireSelf(Long team, Long actor, LoyaltyQueryReader.Catalog own, UUID ruleId, LoyaltyQueryExternalStates.Snapshot state) {
        requireCurrent(team, actor, state); var program = program(own); rule(own, ruleId, true);
        requireEligible(program, team, actor, state);
    }

    private int balance(LoyaltyQueryReader.Catalog own) { return own.account() == null ? 0 : own.account().getBalance(); }

    private SelfAwardPreview selfPreview(Long team, Long actor, LoyaltyQueryReader.Catalog own, LoyaltyQueryExternalStates.Snapshot state) {
        return mapper.selfAwardPreview(team, state.teamName(team), own.program(), mapper.rule(own.rule()), balance(own), true,
                reader.awardedToday(team, actor, own.rule().getId(), time.today()));
    }

    private Long verifiedUser(String token) {
        try { return memberCardService.verifyPlatformQr(token).userId(); }
        catch (BusinessException failure) {
            if (failure.getErrorCode() instanceof MembershipErrorCode) throw new BusinessException(LoyaltyErrorCode.LOYALTY_010);
            throw failure;
        }
    }

    private RequestReason externalFailure(LoyaltyAwardRequestEntity row, LoyaltyProgramEntity program,
            LoyaltyQueryExternalStates.Snapshot state) {
        if (!state.teamActive(row.getTeamId())) return RequestReason.TEAM_INACTIVE;
        var location = state.locations().get(row.getSourceLocationId());
        if (location == null || !location.active() || !row.getTeamId().equals(location.teamId())) return RequestReason.LOCATION_INACTIVE;
        if (!state.userActive(row.getUserId())) return RequestReason.USER_INACTIVE;
        if (program != null && !eligible(program, row.getTeamId(), row.getUserId(), state)) return RequestReason.INELIGIBLE;
        return null;
    }

    private LoyaltyRequestActionOutcome act(Long actor, Long team, LoyaltyAwardRequestEntity row, long version,
            String code, RequestOperation operation, UUID key, byte[] hash) {
        var own = reader.catalog(team, row.getUserId());
        var state = current(team, row.getUserId(), Set.of(row.getSourceLocationId()));
        var result = writer.act(new LoyaltyRequestActionCommand(team, actor, row.getId(), version, code, operation, key, hash,
                externalFailure(row, own.program(), state)));
        if (result.errorCode() != null) throw new BusinessException(result.errorCode());
        return result;
    }

    private TransactionResponse transactionReceipt(Long team, Long actor, RequestOperation operation, UUID key, byte[] hash) {
        var row = reader.transactionReceipt(team, actor, operation, key);
        if (row == null) return null;
        samePayload(row.getPayloadHash(), hash);
        return mapper.transaction(row);
    }

    private SelfAwardResult submissionReceipt(Long team, Long actor, UUID key, byte[] hash) {
        var transaction = transactionReceipt(team, actor, RequestOperation.SELF_SUBMIT, key, hash);
        if (transaction != null) return new SelfAwardResult(SelfAwardOutcome.AWARDED, transaction, null);
        var row = reader.submissionReceipt(team, actor, key);
        if (row == null) return null;
        samePayload(row.getSubmitPayloadHash(), hash);
        return new SelfAwardResult(SelfAwardOutcome.SUBMITTED, null, query.myRequest(actor, row.getId()));
    }

    private AwardRequestResponse actionReceipt(Long team, Long actor, RequestOperation operation, UUID key, byte[] hash, boolean staff) {
        var row = reader.actionReceipt(team, actor, operation, key);
        if (row == null) return null;
        samePayload(row.getActionPayloadHash(), hash);
        return staff ? query.staffRequest(actor, team, row.getId()) : query.myRequest(actor, row.getId());
    }

    private void samePayload(byte[] stored, byte[] supplied) {
        if (stored == null || !MessageDigest.isEqual(stored, supplied)) throw new BusinessException(LoyaltyErrorCode.LOYALTY_006);
    }

    /** Writerの独立TX終了後だけ再読する。認可エラー・無関係な整合性違反は回収しない。 */
    private <T> T execute(Runnable authorization, Supplier<T> receipt, Runnable newOperationGate, Supplier<T> operation) {
        for (int attempt = 0; attempt < 3; attempt++) {
            authorization.run();
            T saved = receipt.get(); if (saved != null) return saved;
            try {
                if (attempt == 0) newOperationGate.run();
                return operation.get();
            }
            catch (BusinessException failure) {
                authorization.run(); saved = receipt.get();
                if (saved != null) return saved;
                throw failure;
            } catch (DataIntegrityViolationException failure) {
                if (!knownUnique(failure)) throw failure;
                authorization.run(); saved = receipt.get();
                if (saved != null) return saved;
                if (attempt == 2) throw failure;
            } catch (LoyaltyPendingSnapshotChangedException failure) {
                if (attempt == 2) throw new BusinessException(LoyaltyErrorCode.LOYALTY_013);
            }
        }
        throw new IllegalStateException("再試行回数の制御に失敗しました");
    }

    private boolean knownUnique(Throwable failure) {
        var names = Set.of("uk_tla_owner", "uk_tlt_receipt", "uk_tlt_daily", "uk_tlar_submit", "uk_tlar_code",
                "uk_tlar_pending", "uk_tlar_action", "uk_tlar_transaction");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint && constraint.getConstraintName() != null) {
                String name = constraint.getConstraintName(); int dot = name.lastIndexOf('.');
                return names.contains(dot < 0 ? name : name.substring(dot + 1));
            }
        }
        return false;
    }

    /** membershipの既知QR業務エラーだけを公開ポイント契約へ変換する。障害は握り潰さない。 */
    private CheckinLocationService.VerifiedTeamLocation verifiedLocation(String token, Long expectedTeam) {
        CheckinLocationService.VerifiedTeamLocation location;
        try { location = locationService.resolveTeamLocationQr(token); }
        catch (BusinessException failure) {
            if (failure.getErrorCode() instanceof MembershipErrorCode) throw new BusinessException(LoyaltyErrorCode.LOYALTY_010);
            throw failure;
        }
        if (expectedTeam != null && !expectedTeam.equals(location.teamId())) throw new BusinessException(LoyaltyErrorCode.LOYALTY_011);
        return location;
    }
}
