package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.Auth2faService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.TransferOwnershipAcceptResponse;
import com.mannschaft.app.role.dto.TransferOwnershipOfferCreateRequest;
import com.mannschaft.app.role.dto.TransferOwnershipOfferResponse;
import com.mannschaft.app.role.entity.OwnershipTransferOfferEntity;
import com.mannschaft.app.role.repository.OwnershipTransferOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * オーナー委譲 承諾型オファーサービス（F01.2・2026-07-18 承諾型化）。
 *
 * <p>従来の即時型オーナー委譲を「打診（PENDING 作成）→ 指名相手の承諾（accept）で委譲実行」の
 * 2 ステップ承諾型に置き換える。指名相手だけが承諾でき（宛先照合＝IDOR 防止）、承諾があって初めて
 * 対象ユーザーを ADMIN 昇格＋発行者を MEMBER 降格する。辞退・期限切れ・取消のいずれでもロールは不変。</p>
 *
 * <p>通常委譲（承諾型 accept）と退会 purge 経由の強制委譲は取り違え防止のため別メソッドに分離する
 * （設計書 H-2）。accept は既存 {@link RoleService#transferOwnership} の「薄いラッパ」であり
 * 無改修流用ではない（設計書 H-3）:</p>
 * <ol>
 *   <li>引数組み替え — {@code currentUserId} には<strong>発行者</strong>（{@code issued_by}）を渡す。</li>
 *   <li>2FA チェックの新規追加 — 承諾者の 2FA 有効性を検証してから呼ぶ（未設定は 422）。</li>
 *   <li>エラー再マッピング — {@code transferOwnership} が投げる {@code ROLE_001} を承諾フロー文脈へ
 *       ({@code 403/404/409/422}) に再マッピングする（{@code ROLE_001} を素通しにしない）。</li>
 * </ol>
 *
 * <p>認可は二層（Controller の {@code @PreAuthorize("isAuthenticated()")}＋本 Service の細粒度認可）。
 * 打診＝ADMIN・承諾/辞退＝指名相手本人・取消＝発行者/ADMIN を本 Service で機械的に検証する。</p>
 *
 * <p>設計書: docs/features/F01.2_org_team_member_role/03_business_logic.md
 * 「オーナー委譲 承諾フロー（2ステップ・承諾型）」。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OwnershipTransferOfferService {

    private static final String SCOPE_TEAM = "TEAM";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_DECLINED = "DECLINED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** 通知種別（{@link com.mannschaft.app.notification.NotificationType} と同値の文字列）。 */
    private static final String NOTIF_OFFERED = "OWNERSHIP_TRANSFER_OFFERED";
    private static final String NOTIF_DECLINED = "OWNERSHIP_TRANSFER_DECLINED";
    /** 通知の sourceType（MEMBER_JOINED 等と同じく USER 起点。sourceId は UUID のため持たない）。 */
    private static final String NOTIF_SOURCE_USER = "USER";

    /** オファーの有効期限（発行から7日）。 */
    private static final int OFFER_TTL_DAYS = 7;

    private final OwnershipTransferOfferRepository offerRepository;
    private final RoleService roleService;
    private final AccessControlService accessControlService;
    private final Auth2faService auth2faService;
    private final NotificationHelper notificationHelper;

    /**
     * オーナー委譲を打診する（PENDING オファーを作成。ロールは変わらない）。
     *
     * @param scopeId     スコープ（チーム/組織）ID
     * @param scopeType   スコープ種別（{@code TEAM} / {@code ORGANIZATION}）
     * @param scopeSlug   スコープの slug（到達通知の actionUrl 生成に用いる。Controller が解決済み）
     * @param request     打診リクエスト（targetUserId）
     * @param actorUserId 実行ユーザー ID（対象スコープの ADMIN）
     * @return 作成されたオファー
     */
    @Transactional
    public TransferOwnershipOfferResponse createOffer(
            Long scopeId, String scopeType, String scopeSlug,
            TransferOwnershipOfferCreateRequest request, Long actorUserId) {
        Long targetUserId = request.targetUserId();

        // 認可（public 入口の二層目）: 打診は当該スコープの ADMIN のみ（DEPUTY_ADMIN 不可・設計書 step2）。
        if (!accessControlService.isAdmin(actorUserId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002); // 403
        }

        // 自己委譲は不可（設計書 step4）。
        if (actorUserId.equals(targetUserId)) {
            throw new BusinessException(RoleErrorCode.ROLE_014); // 422
        }

        // 対象が当該スコープの所属メンバーであること（設計書 step4）。
        if (!accessControlService.isMember(targetUserId, scopeId, scopeType)) {
            throw new BusinessException(RoleErrorCode.ROLE_013); // 404
        }

        // 2FA 必須チェック（設計書 step5）: 無駄なオファー作成を防ぐため打診時にも対象の 2FA を確認する
        // （承諾時に承諾者本人の 2FA を再チェックする）。
        if (!auth2faService.isTwoFactorEnabled(targetUserId)) {
            throw new BusinessException(RoleErrorCode.ROLE_010); // 422
        }

        // 重複打診防止（設計書 step6）: 同一スコープに有効な PENDING があれば 409。
        // 古いオファーは取消（cancel）してから再打診する運用とする。
        if (!findPendingOffersInScope(scopeId, scopeType).isEmpty()) {
            throw new BusinessException(RoleErrorCode.ROLE_011); // 409
        }

        OwnershipTransferOfferEntity offer = OwnershipTransferOfferEntity.builder()
                .issuedBy(actorUserId)
                .targetUserId(targetUserId)
                .status(STATUS_PENDING)
                .expiresAt(LocalDateTime.now().plusDays(OFFER_TTL_DAYS))
                .build();
        // スコープ種別に応じて XOR カラムをセット（chk_oto_scope: team_id と organization_id の一方のみ）。
        if (SCOPE_TEAM.equals(scopeType)) {
            offer.setTeamId(scopeId);
        } else {
            offer.setOrganizationId(scopeId);
        }
        offer = offerRepository.save(offer);

        log.info("オーナー委譲 打診作成: scopeType={}, scopeId={}, offerId={}, issuedBy={}, target={}",
                scopeType, scopeId, offer.getId(), actorUserId, targetUserId);

        // 到達通知（設計書 step9）: 対象ユーザーへ「管理者就任の打診が届いた」通知を発火し、
        // actionUrl で受信側の承諾/辞退カード（?offerId= ディープリンク）へ導線を張る。
        // これが無いと打診相手がオファーに気づけず承諾画面へ到達できない（承諾型が成立しない）。
        // sourceId は UUID のため持たない（sourceType=USER・sourceId=null で visibility ガードは対象外）。
        notificationHelper.notify(
                targetUserId,
                NOTIF_OFFERED,
                NotificationPriority.HIGH,
                "管理者就任の打診が届きました",
                "あなたに管理者（オーナー）就任が打診されています。承諾すると管理者になります。",
                NOTIF_SOURCE_USER,
                null,
                notificationScopeType(scopeType),
                scopeId,
                buildMembersDeepLink(scopeType, scopeSlug, offer.getId()),
                actorUserId);

        // 表示名は FE 側のメンバー一覧で userId から解決する（role ドメインから user ドメインの
        // 表示名を跨いで引かない＝越境 Repository 依存回避。D-3）。
        return new TransferOwnershipOfferResponse(
                offer.getId(),
                offer.getStatus(),
                new TransferOwnershipOfferResponse.UserBrief(targetUserId, null),
                new TransferOwnershipOfferResponse.UserBrief(actorUserId, null),
                offer.getExpiresAt());
    }

    /**
     * オファーを承諾する（＝委譲を実行。対象→ADMIN 昇格・発行者→MEMBER 降格）。
     *
     * <p>指名相手本人のみ承諾可（宛先照合 = IDOR 防止）。承諾者の 2FA 設定を検証してから
     * 既存 {@link RoleService#transferOwnership} を薄いラッパ層で呼ぶ（設計書 H-3）。</p>
     *
     * @param scopeId     スコープ ID
     * @param scopeType   スコープ種別
     * @param offerId     オファー ID
     * @param actorUserId 実行ユーザー ID（指名相手本人）
     * @return 委譲結果（新 ADMIN / 旧 ADMIN）
     */
    @Transactional
    public TransferOwnershipAcceptResponse acceptOffer(
            Long scopeId, String scopeType, UUID offerId, Long actorUserId) {
        OwnershipTransferOfferEntity offer = loadScopedOffer(offerId, scopeId, scopeType);

        // 状態確認（設計書 step1）: PENDING かつ未期限のみ承諾可。
        requirePending(offer);
        if (offer.getExpiresAt().isBefore(LocalDateTime.now())) {
            // 期限切れ。EXPIRED マークは同一 tx throw では巻き戻るため永続させず、
            // 状態不整合として拒否する（EXPIRED への確定は期限切れ掃引バッチの責務）。
            throw new BusinessException(RoleErrorCode.ROLE_012); // 409
        }

        // 宛先照合（設計書 step2・IDOR 防止）: 指名相手本人のみ。第三者は 403（ROLE_009）。
        // 2FA チェックより前に行い、第三者には 2FA の有無を問わず一律 403 を返す。
        requireAddressee(offer, actorUserId);

        // 2FA 再チェック（設計書 step3）: 承諾者本人が 2FA 設定済みであること。未設定は 422。
        if (!auth2faService.isTwoFactorEnabled(actorUserId)) {
            throw new BusinessException(RoleErrorCode.ROLE_010); // 422
        }

        // 薄いラッパで既存 transferOwnership を呼ぶ（設計書 H-3）:
        //  currentUserId には発行者（issued_by）を渡す（降格対象を誤らないため）。
        //  transferOwnership が投げる ROLE_001（発行者が既に非 ADMIN・対象が非所属等、
        //  オファー発行後の状態変化）は承諾フロー文脈で 409（ROLE_012）へ再マッピングする。
        try {
            roleService.transferOwnership(scopeId, scopeType, offer.getIssuedBy(), actorUserId);
        } catch (BusinessException e) {
            if (RoleErrorCode.ROLE_001.getCode().equals(e.getErrorCode().getCode())) {
                throw new BusinessException(RoleErrorCode.ROLE_012); // 409
            }
            throw e;
        }

        LocalDateTime now = LocalDateTime.now();
        offer.setStatus(STATUS_ACCEPTED);
        offer.setAcceptedAt(now);
        offer.setResolvedAt(now);
        offerRepository.save(offer);

        log.info("オーナー委譲 承諾（委譲実行）: scopeType={}, scopeId={}, offerId={}, from={}, to={}",
                scopeType, scopeId, offerId, offer.getIssuedBy(), actorUserId);

        return new TransferOwnershipAcceptResponse(
                new TransferOwnershipAcceptResponse.MemberBrief(actorUserId, null, "ADMIN"),
                new TransferOwnershipAcceptResponse.MemberBrief(offer.getIssuedBy(), null, "MEMBER"));
    }

    /**
     * オファーを辞退する（{@code status=DECLINED}。ロール不変）。指名相手本人のみ。
     *
     * @param scopeId     スコープ ID
     * @param scopeType   スコープ種別
     * @param scopeSlug   スコープの slug（発行者への辞退通知の actionUrl 生成に用いる）
     * @param offerId     オファー ID
     * @param actorUserId 実行ユーザー ID（指名相手本人）
     */
    @Transactional
    public void declineOffer(Long scopeId, String scopeType, String scopeSlug, UUID offerId, Long actorUserId) {
        OwnershipTransferOfferEntity offer = loadScopedOffer(offerId, scopeId, scopeType);
        requirePending(offer);
        requireAddressee(offer, actorUserId); // 宛先照合（IDOR 防止）: 指名相手本人のみ辞退可。

        offer.setStatus(STATUS_DECLINED);
        offer.setResolvedAt(LocalDateTime.now());
        offerRepository.save(offer);

        log.info("オーナー委譲 辞退: scopeType={}, scopeId={}, offerId={}, by={}",
                scopeType, scopeId, offerId, actorUserId);

        // 発行者へ辞退通知（設計書 step 辞退「発行者へ通知」）。actionUrl は当該スコープのメンバー一覧。
        notificationHelper.notify(
                offer.getIssuedBy(),
                NOTIF_DECLINED,
                NotificationPriority.NORMAL,
                "オーナー委譲の打診が辞退されました",
                "打診したメンバーが管理者（オーナー）就任を辞退しました。",
                NOTIF_SOURCE_USER,
                null,
                notificationScopeType(scopeType),
                scopeId,
                buildMembersLink(scopeType, scopeSlug),
                actorUserId);
    }

    /**
     * 自分（指名相手）宛の有効な（PENDING）オファー一覧を取得する（受信インボックス）。
     *
     * <p>認可＝本人限定（IDOR 防止）: {@code findByTargetUserIdAndStatus} は常に呼び出し元自身の
     * {@code targetUserId} で絞るため、第三者が他人宛のオファーを取得する経路が構造的に存在しない。
     * 表示名は打診レスポンスと同様に FE 側のメンバー一覧で解決する（越境 Repository 依存回避）。</p>
     *
     * @param actorUserId 実行ユーザー ID（＝指名相手本人）
     * @return 本人宛の PENDING オファー一覧
     */
    public List<TransferOwnershipOfferResponse> listMyPendingOffers(Long actorUserId) {
        return offerRepository.findByTargetUserIdAndStatus(actorUserId, STATUS_PENDING).stream()
                .map(offer -> new TransferOwnershipOfferResponse(
                        offer.getId(),
                        offer.getStatus(),
                        new TransferOwnershipOfferResponse.UserBrief(offer.getTargetUserId(), null),
                        new TransferOwnershipOfferResponse.UserBrief(offer.getIssuedBy(), null),
                        offer.getExpiresAt()))
                .toList();
    }

    /**
     * オファーを取消す（{@code status=CANCELLED}。ロール不変）。発行者または対象スコープ ADMIN のみ。
     *
     * @param scopeId     スコープ ID
     * @param scopeType   スコープ種別
     * @param offerId     オファー ID
     * @param actorUserId 実行ユーザー ID（発行者 or ADMIN）
     */
    @Transactional
    public void cancelOffer(Long scopeId, String scopeType, UUID offerId, Long actorUserId) {
        OwnershipTransferOfferEntity offer = loadScopedOffer(offerId, scopeId, scopeType);
        requirePending(offer);

        // 認可: 発行者本人、または当該スコープの ADMIN のみ取消可。
        boolean isIssuer = offer.getIssuedBy().equals(actorUserId);
        if (!isIssuer && !accessControlService.isAdmin(actorUserId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002); // 403
        }

        offer.setStatus(STATUS_CANCELLED);
        offer.setResolvedAt(LocalDateTime.now());
        offerRepository.save(offer);

        log.info("オーナー委譲 取消: scopeType={}, scopeId={}, offerId={}, by={}",
                scopeType, scopeId, offerId, actorUserId);
    }

    /**
     * 退会（アカウント purge）に伴う最後の ADMIN 承継のための強制委譲（承諾スキップ・2FA チェックなし）。
     *
     * <p>通常の承諾型 accept とは別経路（設計書 H-2・GDPR 30 日タイムリミット順守）。
     * purge 経路（{@code AccountPurgeService} / {@code RolePurgeEventListener}）から同期即時で呼ぶ。
     * オファーを介さず直接 {@link RoleService#transferOwnership} を呼び、監査に強制委譲であることを明示する。</p>
     *
     * @param scopeId        スコープ ID
     * @param scopeType      スコープ種別
     * @param issuerUserId   承継元（退会する現 ADMIN）ユーザー ID
     * @param targetUserId   承継先ユーザー ID
     */
    @Transactional
    public void forceTransferForPurge(
            Long scopeId, String scopeType, Long issuerUserId, Long targetUserId) {
        // 承諾スキップの即時委譲。承諾型オファーを介さず、2FA も確認しない（退会完遂を優先）。
        roleService.transferOwnership(scopeId, scopeType, issuerUserId, targetUserId);

        // 監査: 強制委譲（forced=true）であることを明示する（設計書 H-2 の監査要件）。
        log.warn("オーナー委譲 強制実行（退会 purge 承継・forced=true）: "
                        + "scopeType={}, scopeId={}, from={}, to={}",
                scopeType, scopeId, issuerUserId, targetUserId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /** スコープ整合を担保してオファーを取得する（別スコープからの参照＝BOLA を 404 で遮断）。 */
    private OwnershipTransferOfferEntity loadScopedOffer(UUID offerId, Long scopeId, String scopeType) {
        Optional<OwnershipTransferOfferEntity> found = SCOPE_TEAM.equals(scopeType)
                ? offerRepository.findByIdAndTeamId(offerId, scopeId)
                : offerRepository.findByIdAndOrganizationId(offerId, scopeId);
        return found.orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_013)); // 404
    }

    /** 同一スコープの PENDING オファー一覧。 */
    private List<OwnershipTransferOfferEntity> findPendingOffersInScope(Long scopeId, String scopeType) {
        return SCOPE_TEAM.equals(scopeType)
                ? offerRepository.findByTeamIdAndStatus(scopeId, STATUS_PENDING)
                : offerRepository.findByOrganizationIdAndStatus(scopeId, STATUS_PENDING);
    }

    /** オファーが PENDING であることを要求する（既処理/辞退/取消/期限確定済みは 409）。 */
    private void requirePending(OwnershipTransferOfferEntity offer) {
        if (!STATUS_PENDING.equals(offer.getStatus())) {
            throw new BusinessException(RoleErrorCode.ROLE_012); // 409
        }
    }

    /** 宛先照合（IDOR 防止）: 指名相手本人でなければ 403（ROLE_009）。 */
    private void requireAddressee(OwnershipTransferOfferEntity offer, Long actorUserId) {
        if (!offer.getTargetUserId().equals(actorUserId)) {
            throw new BusinessException(RoleErrorCode.ROLE_009); // 403
        }
    }

    /** スコープ種別を通知スコープ種別へ写す（TEAM / ORGANIZATION）。 */
    private NotificationScopeType notificationScopeType(String scopeType) {
        return SCOPE_TEAM.equals(scopeType)
                ? NotificationScopeType.TEAM
                : NotificationScopeType.ORGANIZATION;
    }

    /** 受信側の承諾/辞退カードへ飛ぶディープリンク（{@code ?offerId=} で FE がカードを表示）。 */
    private String buildMembersDeepLink(String scopeType, String scopeSlug, UUID offerId) {
        return buildMembersLink(scopeType, scopeSlug) + "?offerId=" + offerId;
    }

    /** 当該スコープのメンバー一覧ページ（辞退通知等・offerId なし）。 */
    private String buildMembersLink(String scopeType, String scopeSlug) {
        String base = SCOPE_TEAM.equals(scopeType) ? "/teams/" : "/organizations/";
        return base + scopeSlug + "/members";
    }
}
