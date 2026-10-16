package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.loyalty.dto.AccountDetail;
import com.mannschaft.app.loyalty.dto.AccountSummary;
import com.mannschaft.app.loyalty.dto.AwardRequestResponse;
import com.mannschaft.app.loyalty.dto.OperationCatalog;
import com.mannschaft.app.loyalty.dto.ProgramSettings;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 読取専用接続契約の骨格。actor active/staff/adminの入口認可はFacade/AccessPolicyが先に行う。
 * <p>Queryは本人userId・TEAM条件と読取権を維持し、公開Service集合snapshotで同じ申請投影を使う。</p>
 * <p>現段階はnull/0骨格。業務実装・green完了・merge可能とは扱わない。</p>
 */
@Service
@Transactional(readOnly = true)
public class LoyaltyQueryService {
    public CursorPagedResponse<AccountSummary> listAccounts(Long actor, String cursor, int limit) { return null; }
    public AccountDetail account(Long actor, Long team) { return null; }
    public CursorPagedResponse<TransactionResponse> transactions(Long actor, Long team, String cursor, int limit) { return null; }
    public OperationCatalog operationCatalog(Long team) { return null; }
    public ProgramSettings settings(Long team) { return null; }
    public CursorPagedResponse<AwardRequestResponse> myRequests(Long actor, String cursor, int limit) { return null; }
    public AwardRequestResponse myRequest(Long actor, UUID request) { return null; }
    public CursorPagedResponse<AwardRequestResponse> staffRequests(Long actor, Long team, RequestFilter status, String cursor, int limit) { return null; }
    public AwardRequestResponse staffRequest(Long actor, Long team, UUID request) { return null; }
    /** 有効な受付QR/対象資格をFacadeで確認した初回bootstrap専用。通常GETの読取権を発行しない。 */
    public AccountDetail verifiedBootstrapAccount(Long actor, Long team) { return null; }
    /** Facadeで認可・本人QRを検証したpreview専用の現残高。 */
    public int balance(Long team, Long user) { return 0; }
}
