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
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/**
 * Controller接続契約の先行骨格。FacadeはTXを持たず、認可・成功receipt・新規検証・Writerを順に接続する。
 * <p>現段階はHTTP先行試練用のnull骨格であり、実装/green完了・merge可能とは扱わない。</p>
 */
@Service
@RequiredArgsConstructor
public class LoyaltyFacade {
    private final LoyaltyAccessPolicy policy;
    private final LoyaltyQueryService query;
    private final LoyaltySettingsWriter settingsWriter;
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
    public ScanPreview scanPreview(Long actor, Long team, ScanPreviewRequest body) { return null; }
    public RedemptionPreview redemptionPreview(Long actor, Long team, RedemptionPreviewRequest body) { return null; }
    public TransactionResponse award(Long actor, Long team, StaffAwardRequest body, String idempotencyKey) { return null; }
    public TransactionResponse redeem(Long actor, Long team, RedeemRequest body, String idempotencyKey) { return null; }
    public SelfAwardBootstrap bootstrap(Long actor, SelfAwardBootstrapRequest body) { return null; }
    public SelfAwardPreview selfPreview(Long actor, Long team, SelfAwardPreviewRequest body) { return null; }
    public SelfAwardResult selfAward(Long actor, Long team, SelfAwardRequest body, String idempotencyKey) { return null; }
    public CursorPagedResponse<AwardRequestResponse> myRequests(Long actor, String cursor, int limit) {
        policy.requireActiveActor(actor); return query.myRequests(actor, cursor, limit);
    }
    public AwardRequestResponse myRequest(Long actor, UUID request) {
        policy.requireActiveActor(actor); return query.myRequest(actor, request);
    }
    public AwardRequestResponse cancel(Long actor, UUID request, RequestActionRequest body, String idempotencyKey) { return null; }
    public CursorPagedResponse<AwardRequestResponse> staffRequests(Long actor, Long team, RequestFilter status, String cursor, int limit) {
        operator(actor, team); policy.requireActiveTeam(team); return query.staffRequests(actor, team, status, cursor, limit);
    }
    public AwardRequestResponse staffRequest(Long actor, Long team, UUID request) {
        operator(actor, team); policy.requireActiveTeam(team); return query.staffRequest(actor, team, request);
    }
    public TransactionResponse approve(Long actor, Long team, UUID request, ApproveRequest body, String idempotencyKey) { return null; }
    public AwardRequestResponse reject(Long actor, Long team, UUID request, RequestActionRequest body, String idempotencyKey) { return null; }

    private void operator(Long actor, Long team) {
        policy.requireActiveActor(actor); policy.requireOperator(actor, team);
    }
    private void settingsAdmin(Long actor, Long team) {
        policy.requireActiveActor(actor); policy.requireSettingsAdmin(actor, team); policy.requireActiveTeam(team);
    }
}
