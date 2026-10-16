package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.CursorPagedResponse;
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

/**
 * Controller接続契約の先行骨格。FacadeはTXを持たず、認可・成功receipt・新規検証・Writerを順に接続する。
 * <p>現段階はHTTP先行試練用のnull骨格であり、実装/green完了・merge可能とは扱わない。</p>
 */
@Service
public class LoyaltyFacade {
    static UUID idempotencyKey(String raw) { return null; }

    public CursorPagedResponse<AccountSummary> listAccounts(Long actor, String cursor, int limit) { return null; }
    public AccountDetail account(Long actor, Long team) { return null; }
    public CursorPagedResponse<TransactionResponse> transactions(Long actor, Long team, String cursor, int limit) { return null; }
    public OperationCatalog operationCatalog(Long actor, Long team) { return null; }
    public ProgramSettings settings(Long actor, Long team) { return null; }
    public ProgramSettings updateSettings(Long actor, Long team, UpdateProgramRequest body) { return null; }
    public RuleResponse createRule(Long actor, Long team, CreateRuleRequest body) { return null; }
    public RuleResponse updateRule(Long actor, Long team, UUID rule, UpdateRuleRequest body) { return null; }
    public RewardResponse createReward(Long actor, Long team, CreateRewardRequest body) { return null; }
    public RewardResponse updateReward(Long actor, Long team, UUID reward, UpdateRewardRequest body) { return null; }
    public ScanPreview scanPreview(Long actor, Long team, ScanPreviewRequest body) { return null; }
    public RedemptionPreview redemptionPreview(Long actor, Long team, RedemptionPreviewRequest body) { return null; }
    public TransactionResponse award(Long actor, Long team, StaffAwardRequest body, String idempotencyKey) { return null; }
    public TransactionResponse redeem(Long actor, Long team, RedeemRequest body, String idempotencyKey) { return null; }
    public SelfAwardBootstrap bootstrap(Long actor, SelfAwardBootstrapRequest body) { return null; }
    public SelfAwardPreview selfPreview(Long actor, Long team, SelfAwardPreviewRequest body) { return null; }
    public SelfAwardResult selfAward(Long actor, Long team, SelfAwardRequest body, String idempotencyKey) { return null; }
    public CursorPagedResponse<AwardRequestResponse> myRequests(Long actor, String cursor, int limit) { return null; }
    public AwardRequestResponse myRequest(Long actor, UUID request) { return null; }
    public AwardRequestResponse cancel(Long actor, UUID request, RequestActionRequest body, String idempotencyKey) { return null; }
    public CursorPagedResponse<AwardRequestResponse> staffRequests(Long actor, Long team, RequestFilter status, String cursor, int limit) { return null; }
    public AwardRequestResponse staffRequest(Long actor, Long team, UUID request) { return null; }
    public TransactionResponse approve(Long actor, Long team, UUID request, ApproveRequest body, String idempotencyKey) { return null; }
    public AwardRequestResponse reject(Long actor, Long team, UUID request, RequestActionRequest body, String idempotencyKey) { return null; }
}
