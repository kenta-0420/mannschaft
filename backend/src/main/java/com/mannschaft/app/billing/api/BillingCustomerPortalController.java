package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.BillingCustomerPortalSessionResponse;
import com.mannschaft.app.billing.api.dto.CreateBillingCustomerPortalSessionRequest;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * PR5 Billing Center: Stripe Customer Portal セッション発行の HTTP 入口
 * （{@code POST /api/v1/me/billing/portal-sessions}・AC-61〜AC-74）。
 *
 * <p><b>認可</b>: 入口は認証必須（{@code isAuthenticated()}）に留め、scope 認可は application service が
 * {@link BillingCustomerPortalScopeGuard} 経由で {@link BillingAccessGuard#canManageByActorId} に委ねる。
 * scope はパスではなく<b>リクエスト本文</b>から来るため SpEL のパス引数では守れない
 * （PR4 の quote / checkout と同じ構造）。</p>
 *
 * <p><b>冪等性（AC-69 / AC-70）</b>: {@code Idempotency-Key} ヘッダ必須。欠落は Spring の
 * {@code MissingRequestHeaderException} → 400。同一キー・同一 body の再送は保存済み応答を replay し、
 * 同一キー・<b>body 相違</b>は {@link BillingDurableIdempotencyService#begin} が hash 不一致として
 * 弾くため <b>Stripe を一度も呼ばない</b>。PR4 の checkout 入口と同一の作法で包む。</p>
 *
 * <p><b>fail-closed（AC-65）</b>: Portal configuration の起動時照合に失敗している間は
 * {@code ENTITLEMENT_027} により <b>503</b> を返す。アプリ全体は起動しており、他の課金機能は使える。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "課金 - Customer Portal", description = "PR5 Stripe Customer Portal セッション発行")
@RequiredArgsConstructor
public class BillingCustomerPortalController {

    private static final String PORTAL_PATH = BillingCustomerPortalApplicationService.IDEMPOTENCY_PATH;
    private static final String METHOD = BillingCustomerPortalApplicationService.IDEMPOTENCY_METHOD;
    private static final String DATA_FIELD = "data";

    private final BillingCustomerPortalApplicationService portalApplicationService;
    private final BillingDurableIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    /**
     * Stripe Customer Portal のセッションを発行する。
     *
     * @param request        対象 scope（return URL は受け取らない）
     * @param idempotencyKey 冪等キー（必須）
     * @return Portal の短命 URL と発行時刻
     */
    @PostMapping("/me/billing/portal-sessions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Customer Portal セッション発行",
            description = "scope は本文で指定し、操作者の課金管理権限をサービス層で検証する。"
                    + "支払方法・請求先情報・請求書履歴のみを開く専用 configuration を用い、"
                    + "PLAN/ADDON の変更・解約は Portal から行えない。Idempotency-Key 必須。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "対象 scope の課金管理権限がない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Customer が ACTIVE でない、又は同一キーで異なる要求が既にある")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "scope ごとの発行回数上限（10 回/時）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Stripe 側の失敗")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "Portal configuration の起動時照合が未成立（fail-closed）")
    public ResponseEntity<ApiResponse<BillingCustomerPortalSessionResponse>> createPortalSession(
            @Valid @RequestBody CreateBillingCustomerPortalSessionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        String requestHash = requestHash(actorId, request);
        String leaseOwner = UUID.randomUUID().toString();
        BillingIdempotencyDecision decision = idempotencyService.begin(
                actorId, METHOD, PORTAL_PATH, idempotencyKey, requestHash, leaseOwner);

        if (decision.kind() == BillingIdempotencyDecisionKind.PROCESSING) {
            throw new BillingIdempotencyProcessingException(decision.retryAfterSeconds());
        }
        if (decision.kind() == BillingIdempotencyDecisionKind.REPLAY) {
            return replay(decision);
        }

        BillingCustomerPortalSessionResponse body;
        try {
            body = portalApplicationService.create(actorId, request, idempotencyKey);
        } catch (RuntimeException e) {
            idempotencyService.fail(decision.id(), leaseOwner);
            throw e;
        }
        ApiResponse<BillingCustomerPortalSessionResponse> envelope = ApiResponse.of(body);
        idempotencyService.complete(decision.id(), leaseOwner, HttpStatus.CREATED.value(),
                writeJson(envelope));
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope);
    }

    /** 保存済み応答をそのまま返す（本処理は再実行しない）。 */
    private ResponseEntity<ApiResponse<BillingCustomerPortalSessionResponse>> replay(
            BillingIdempotencyDecision decision) {
        if (decision.responseJson() == null || decision.responseStatus() == null) {
            // FAILED 確定済み（本文を保存していない）。失敗を再現せず、新しいキーでの再送を促す。
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
        try {
            JsonNode data = objectMapper.readTree(decision.responseJson()).get(DATA_FIELD);
            if (data == null) {
                throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
            }
            return ResponseEntity.status(decision.responseStatus())
                    .body(ApiResponse.of(objectMapper.treeToValue(
                            data, BillingCustomerPortalSessionResponse.class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to replay stored idempotent response", e);
        }
    }

    /**
     * request hash（SHA-256 hex 64 文字）。actor / HTTP method / request path / 本文 JSON を
     * 区切り付きで連結して取る（PR4 checkout 入口と同一の作法）。
     */
    private String requestHash(Long actorId, Object request) {
        String canonical = String.join("\n", String.valueOf(actorId), METHOD, PORTAL_PATH,
                writeJson(request));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize billing portal payload", e);
        }
    }
}
