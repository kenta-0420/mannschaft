package com.mannschaft.app.provisioning.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationResponse;
import com.mannschaft.app.provisioning.dto.ProvisioningOrganizationCreateRequest;
import com.mannschaft.app.provisioning.dto.ProvisioningTeamCreateRequest;
import com.mannschaft.app.provisioning.repository.ProvisioningInvitationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 柱②-2: 販促プロビジョニングサービス（SYSTEM_ADMIN 側: 作成・一覧・再送・取消）。
 *
 * <p>正本: .claude/campaigns/2026-09-01-org-governance.md 柱②。
 * SYSTEM_ADMIN が組織/チームを PROVISIONED 状態で事前作成し、管理予定者のメールへ
 * ADMIN 招待を送る。承諾は {@link ProvisioningAcceptanceService} が担う。</p>
 *
 * <h2>認可は二層</h2>
 * <p>Controller の {@code SecurityConfig}（{@code /api/v1/system-admin/**} は
 * {@code hasRole("SYSTEM_ADMIN")}）に加え、本 Service 自身も
 * {@link AccessControlService#checkSystemAdmin(Long)} で細粒度認可を行う（AC2）。
 * Service 単体で呼ばれた場合にも権限漏れが起きない設計とするため。</p>
 *
 * <p>本 PR では試練（受け入れテスト）のみを設置する（骨格は
 * {@link UnsupportedOperationException} を投げる）。実装は後続 PR（出陣）で行う。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProvisioningService {

    private final ProvisioningInvitationRepository invitationRepository;
    private final AccessControlService accessControlService;

    /**
     * 組織を PROVISIONED 状態で事前作成し、管理予定者へ ADMIN 招待メールを送る。
     *
     * @param actorUserId 実行ユーザー ID（SYSTEM_ADMIN であること）
     * @param request     作成リクエスト
     * @return 発行された招待（一覧応答と同型。平文トークンはこの戻り値には含まない）
     */
    @Transactional
    public ProvisioningInvitationResponse createOrganization(
            Long actorUserId, ProvisioningOrganizationCreateRequest request) {
        // TODO 出陣で実装:
        //  1. accessControlService.checkSystemAdmin(actorUserId)（AC2: Service単体でも403）
        //  2. inviteEmail のバリデーション（AC13: 不正/空は400）
        //  3. OrganizationEntity を lifecycleStatus=PROVISIONED, visibility=PRIVATE で保存（AC3）
        //  4. SecretTokenVault.issueBase64Url() でトークン発行 → ProvisioningInvitationEntity を
        //     status=PENDING, expiresAt=now+7日, tokenHash=hash で保存（AC3）
        //  5. EmailOutboxService.enqueue で ADMIN 招待メールを outbox 投入（平文トークンはメール本文のみ）
        //  6. AuditLogService.record で監査記録（AC15）
        throw new UnsupportedOperationException("ProvisioningService#createOrganization is not implemented yet");
    }

    /**
     * チームを PROVISIONED 状態で事前作成し、管理予定者へ ADMIN 招待メールを送る。
     *
     * @param actorUserId 実行ユーザー ID（SYSTEM_ADMIN であること）
     * @param request     作成リクエスト
     * @return 発行された招待
     */
    @Transactional
    public ProvisioningInvitationResponse createTeam(Long actorUserId, ProvisioningTeamCreateRequest request) {
        // TODO 出陣で実装: createOrganization と同型（対象が TeamEntity）。
        throw new UnsupportedOperationException("ProvisioningService#createTeam is not implemented yet");
    }

    /**
     * 招待の一覧を返す（0 件なら空配列・AC14）。
     *
     * @param actorUserId 実行ユーザー ID（SYSTEM_ADMIN であること）
     * @return 招待一覧
     */
    public List<ProvisioningInvitationResponse> list(Long actorUserId) {
        // TODO 出陣で実装: accessControlService.checkSystemAdmin(actorUserId) の後、
        //  invitationRepository.findAll() 相当を DTO 変換して返す（0件は空配列で200）。
        throw new UnsupportedOperationException("ProvisioningService#list is not implemented yet");
    }

    /**
     * 招待を再送する（旧行を CANCELLED にし、新しいトークン・新しい行を発行する）。
     *
     * @param actorUserId  実行ユーザー ID（SYSTEM_ADMIN であること）
     * @param invitationId 対象招待 ID（旧行）
     * @return 新規発行された招待
     */
    @Transactional
    public ProvisioningInvitationResponse resend(Long actorUserId, UUID invitationId) {
        // TODO 出陣で実装（AC8）: 旧行を status=CANCELLED, resolvedAt=now に更新し、
        //  新しいトークン・新しい ProvisioningInvitationEntity 行を PENDING で発行する。
        //  旧トークンでの accept は以後 404/409 になる。
        throw new UnsupportedOperationException("ProvisioningService#resend is not implemented yet");
    }

    /**
     * 招待を取消す（{@code status=CANCELLED}）。
     *
     * @param actorUserId  実行ユーザー ID（SYSTEM_ADMIN であること）
     * @param invitationId 対象招待 ID
     */
    @Transactional
    public void cancel(Long actorUserId, UUID invitationId) {
        // TODO 出陣で実装（AC8）: status=CANCELLED, resolvedAt=now に更新する。
        throw new UnsupportedOperationException("ProvisioningService#cancel is not implemented yet");
    }
}
