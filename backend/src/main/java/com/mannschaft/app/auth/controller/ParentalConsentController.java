package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.ApproveConsentRequest;
import com.mannschaft.app.auth.dto.ChildLinkResponse;
import com.mannschaft.app.auth.dto.InvitationResponse;
import com.mannschaft.app.auth.dto.InviteParentRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.ParentLinkResponse;
import com.mannschaft.app.auth.dto.RejectConsentRequest;
import com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意管理コントローラー。
 *
 * <p>未成年ユーザーの保護者招待・承認・否認・リンク解除を管理するエンドポイント群を提供する。
 * 認証不要のエンドポイント（/approve、/reject）は SecurityConfig で permitAll 設定済み。</p>
 */
@RestController
@RequestMapping("/api/v1/parental-consent")
@Tag(name = "保護者同意管理")
@RequiredArgsConstructor
public class ParentalConsentController {

    private final ParentalConsentService parentalConsentService;
    private final AuthenticationCriticalOperationGuard authenticationCriticalOperationGuard;

    // ========================================
    // 子ユーザー側 — 招待操作
    // ========================================

    /**
     * 保護者を招待する。招待メールを保護者のメールアドレスに送信する。
     * レートリミット・自己招待防止・PENDING 上限・重複チェックを行う。
     */
    @SelfScopedEndpoint("childUserId は SecurityUtils.getCurrentUserId() のみで決まり、"
            + "ParentalConsentService#inviteParent は招待の発行元スコープとしてその値のみを使う"
            + "（req.getParentEmail() は宛先の平文メールで DB 検索キーではない。他人の識別子を受け取らない）")
    @PostMapping("/invitations")
    @Operation(summary = "保護者招待送信", description = "子ユーザーが保護者のメールアドレスに同意確認メールを送信する")
    public ResponseEntity<ApiResponse<MessageResponse>> inviteParent(
            @Valid @RequestBody InviteParentRequest req) {
        Long childUserId = SecurityUtils.getCurrentUserId();
        parentalConsentService.inviteParent(childUserId, req.getParentEmail());
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("招待メールを送信しました")));
    }

    /**
     * 子ユーザーが送信済みの招待一覧を取得する。
     */
    @SelfScopedEndpoint("childUserId は SecurityUtils.getCurrentUserId() のみで決まり、"
            + "ParentalConsentService#getInvitations はその値のみを検索条件に使う"
            + "（parentalConsentLinkRepository.findByChildUserId。他人の識別子を受け取る余地が無い）")
    @GetMapping("/invitations")
    @Operation(summary = "送信済み招待一覧取得", description = "子ユーザーが送信した保護者招待の一覧を取得する")
    public ResponseEntity<ApiResponse<List<InvitationResponse>>> getInvitations() {
        Long childUserId = SecurityUtils.getCurrentUserId();
        List<InvitationResponse> list = parentalConsentService.getInvitations(childUserId)
                .stream()
                .map(InvitationResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * PENDING 状態の招待を取り消す（子ユーザー操作）。
     * PENDING 以外の状態の招待は取り消せない。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ParentalConsentService#revokeInvitation}
     * （{@code ParentalConsentService.java:152-168}）が linkId で {@code ParentalConsentLinkEntity}
     * を取得し、{@code link.getChildUserId()} と呼出ユーザーの ID を実データで一致検証する。
     * 不一致・PENDING 以外は AUTH_005 で拒否する（認可根治戦役 Wave5 監査済）。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/invitations/{linkId}")
    @Operation(summary = "招待取消", description = "PENDING 状態の保護者招待を取り消す")
    public ResponseEntity<ApiResponse<MessageResponse>> cancelInvitation(
            @Parameter(description = "招待リンク ID（UUID）")
            @PathVariable String linkId) {
        Long childUserId = SecurityUtils.getCurrentUserId();
        parentalConsentService.revokeInvitation(linkId, childUserId);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("招待を取消しました")));
    }

    // ========================================
    // 子ユーザー側 — 保護者リンク操作
    // ========================================

    /**
     * 子ユーザーの承認済み保護者一覧を取得する。
     */
    @SelfScopedEndpoint("childUserId は SecurityUtils.getCurrentUserId() のみで決まり、"
            + "ParentalConsentService#getApprovedParents はその値のみを検索条件に使う"
            + "（parentalConsentLinkRepository.findByChildUserIdAndStatus。他人の識別子を受け取らない）")
    @GetMapping("/parents")
    @Operation(summary = "承認済み保護者一覧取得", description = "子ユーザーに紐付く承認済みの保護者リンク一覧を取得する")
    public ResponseEntity<ApiResponse<List<ParentLinkResponse>>> getParents() {
        Long childUserId = SecurityUtils.getCurrentUserId();
        List<ParentLinkResponse> list = parentalConsentService.getApprovedParents(childUserId)
                .stream()
                .map(ParentLinkResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * 子ユーザー側から保護者リンクを解除する。
     * 最後の承認済みリンクは解除不可（AUTH_064）。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ParentalConsentService#removeParentalLink}
     * （{@code ParentalConsentService.java:188-211}）が linkId で {@code ParentalConsentLinkEntity}
     * を取得し、{@code link.getChildUserId()} と呼出ユーザーの ID を実データで一致検証する。
     * 不一致・APPROVED 以外は AUTH_005 で拒否する（認可根治戦役 Wave5 監査済）。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/parents/{linkId}")
    @Operation(summary = "保護者リンク解除（子側）", description = "子ユーザーが自分の保護者リンクを解除する（最後のリンクは解除不可）")
    public ResponseEntity<ApiResponse<MessageResponse>> removeParent(
            @Parameter(description = "解除対象のリンク ID（UUID）")
            @PathVariable String linkId) {
        // 後見切替セッション中（acting-as）の親リンク削除を禁止（03_security §3.2 なりすまし防止の安全境界）。
        // 本 EP は「子」が操作する建付けのため、保護者が子として acting-as して共同親権者のリンクを
        // 削除する経路を塞ぐ（認証クリティカル相当）。
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long childUserId = SecurityUtils.getCurrentUserId();
        parentalConsentService.removeParentalLink(linkId, childUserId);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("保護者リンクを解除しました")));
    }

    // ========================================
    // 保護者側 — 承認・否認操作（認証不要）
    // ========================================

    /**
     * 保護者が同意を承認する。トークン検証後、子ユーザーを ACTIVE に遷移させる。
     * このエンドポイントは SecurityConfig で permitAll 設定されており、認証不要。
     *
     * <p>認証済みユーザーが承認する場合は parentUserId を自動取得する。
     * 未認証の場合（メールリンクから直接アクセス）は parentUserId = null として扱う。</p>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 認可は capability トークン
     * （招待メールに封入されるワンタイムトークン）で実施する。
     * {@code ParentalConsentService.java:224-236}（{@code getApprovalRequest}）が
     * {@code authTokenService.hashToken(token)} でハッシュ化した値を
     * {@code parentalConsentLinkRepository.findByTokenHash} と照合し、
     * (1) 該当リンク不在 (2) ステータスが {@code PENDING} 以外 (3) {@code expiresAt} 超過
     * のいずれかで {@code AUTH_060} を throw して中断する。
     * さらに {@code ParentalConsentService.java:250-252} で自己承認（子＝保護者）を
     * {@code AUTH_062} で、{@code :257-262} で未成年保護者を {@code AUTH_063} で拒否する。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/approve")
    @Operation(summary = "保護者同意承認", description = "保護者がトークンを使って同意を承認する（認証不要）")
    public ResponseEntity<ApiResponse<MessageResponse>> approve(
            @Valid @RequestBody ApproveConsentRequest req,
            HttpServletRequest request) {
        // 認証済みの場合は parentUserId を取得し自己承認チェックを行う
        // 未認証（メールリンクから直接）の場合は null（自己承認チェックはスキップ）
        Long parentUserId = SecurityUtils.getCurrentUserIdOrNull();
        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        parentalConsentService.approveParentalConsent(req.getToken(), parentUserId, ipAddress);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("保護者同意を承認しました")));
    }

    /**
     * 保護者が同意を否認する。全保護者が否認した場合、子アカウントは論理削除される。
     * このエンドポイントは SecurityConfig で permitAll 設定されており、認証不要。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@link #approve} と同じ
     * capability トークン検証で認可する。{@code ParentalConsentService.java:300} の
     * {@code rejectParentalConsent} が冒頭で {@code getApprovalRequest(token)}
     * （{@code ParentalConsentService.java:224-236}）を呼び、トークンハッシュ照合・
     * {@code PENDING} 判定・有効期限判定に失敗すれば {@code AUTH_060} を throw して中断する。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/reject")
    @Operation(summary = "保護者同意否認", description = "保護者がトークンを使って同意を否認する（認証不要）")
    public ResponseEntity<ApiResponse<MessageResponse>> reject(
            @Valid @RequestBody RejectConsentRequest req,
            HttpServletRequest request) {
        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        parentalConsentService.rejectParentalConsent(req.getToken(), ipAddress);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("保護者同意を否認しました")));
    }

    // ========================================
    // 保護者ユーザー側 — 子一覧・リンク解除操作
    // ========================================

    /**
     * 保護者ユーザーが監護している子ユーザーの APPROVED リンク一覧を取得する。
     * PII リスク低減のため、子の表示名は null で返す。
     */
    @SelfScopedEndpoint("parentUserId は SecurityUtils.getCurrentUserId() のみで決まり、"
            + "ParentalConsentService#getChildrenAsParent はその値のみを検索条件に使う"
            + "（parentalConsentLinkRepository.findByParentUserIdAndStatus。他人の識別子を受け取らない）")
    @GetMapping("/children")
    @Operation(summary = "子ユーザー一覧取得（保護者）", description = "自分が保護者として登録されている子ユーザーの一覧を取得する")
    public ResponseEntity<ApiResponse<List<ChildLinkResponse>>> getChildren() {
        Long parentUserId = SecurityUtils.getCurrentUserId();
        List<ChildLinkResponse> list = parentalConsentService.getChildrenAsParent(parentUserId)
                .stream()
                // PII リスク低減のため childDisplayName は null（クライアントが "非公開" と表示する）
                .map(link -> ChildLinkResponse.from(link, null))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * 保護者ユーザー側から子リンクを解除する。
     * 子の唯一の保護者かつ子が PENDING_PARENTAL_CONSENT 状態の場合は解除不可（AUTH_065）。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ParentalConsentService#removeParentalLinkAsParent}
     * （{@code ParentalConsentService.java:347-370}）が linkId で {@code ParentalConsentLinkEntity}
     * を取得し、{@code link.getParentUserId()} と呼出ユーザーの ID を実データで一致検証する。
     * 不一致・APPROVED 以外は AUTH_005 で拒否する（認可根治戦役 Wave5 監査済）。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/children/{linkId}")
    @Operation(summary = "保護者リンク解除（保護者側）", description = "保護者ユーザーが自分と子ユーザーのリンクを解除する")
    public ResponseEntity<ApiResponse<MessageResponse>> removeChildLink(
            @Parameter(description = "解除対象のリンク ID（UUID）")
            @PathVariable String linkId) {
        Long parentUserId = SecurityUtils.getCurrentUserId();
        parentalConsentService.removeParentalLinkAsParent(linkId, parentUserId);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("保護者リンクを解除しました")));
    }
}
