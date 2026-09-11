package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.BillingCancelRequest;
import com.mannschaft.app.billing.api.dto.BillingContractCancelResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.function.Supplier;

/**
 * Billing Center PR6a: 解約・解約撤回の HTTP 入口（正本 05_billing_center.md:334-335・BC-06 / BC-19）。
 *
 * <pre>
 * POST   /api/v1/me/billing/contracts/{contractId}/cancel   解約（期末解約の予約）
 * DELETE /api/v1/me/billing/contracts/{contractId}/cancel   解約撤回
 * </pre>
 *
 * <p><b>スコープ別に増やさない（殿の設計判断 D6）</b>: 正本が定めるのは {@code /me} の2本だけである。
 * TEAM / ORG の契約も {@code /me} 配下の {@code contractId} で操作し、認可は
 * {@link BillingContractCancelApplicationService} が契約 → スコープを解決して判定する
 * （既存 controller が3スコープなのは旧 create/change/delete API であって、本 API を6本へ
 * 増やす根拠にはならない）。</p>
 *
 * <p><b>冪等性</b>: 両入口とも {@code Idempotency-Key} ヘッダ必須（欠落は Spring が 400）。
 * {@link BillingCheckoutController} と<b>同一の流儀</b>で {@link BillingDurableIdempotencyService} の
 * {@code begin} / {@code complete} を通す。request hash は
 * 「actor / HTTP method / 具体 request path / 本文 JSON」の連結の SHA-256 であり、DELETE も本文
 * {@code {"version":N}} を受けることで POST と同じ算式を共有する。</p>
 *
 * <p><b>認可を冪等台帳より先に行う</b>: 権限の無い要求で台帳に行を作ると、以後その actor / key の
 * 組み合わせが塞がれる（PR5 の実在欠陥）。本 controller は認可を含む本処理を
 * {@code begin} の後に置きつつ、application service の入口で最初に認可を判定する。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "課金 - 解約", description = "Billing Center PR6a 期末解約の予約と撤回")
@RequiredArgsConstructor
public class BillingContractCancelController {

    /** 解約 / 撤回の唯一のパス（D6）。冪等台帳の {@code request_path} には実 UUID を含む具体 URI を刻む。 */
    private static final String CANCEL_PATH_FORMAT = "/api/v1/me/billing/contracts/%s/cancel";
    private static final String METHOD_CANCEL = "POST";
    private static final String METHOD_RESUME = "DELETE";
    private static final String DATA_FIELD = "data";

    private final BillingContractCancelApplicationService cancelApplicationService;
    private final BillingDurableIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    /**
     * 期末解約を予約する（無償契約は即時失効）。
     *
     * @param contractId     対象契約
     * @param request        CAS 期待値（{@code version}）
     * @param idempotencyKey 冪等キー（必須・36文字以内）
     * @return 解約後の状態
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の既存課金契約をGate状態にかかわらず解約可能にするため")
    @PostMapping("/me/billing/contracts/{contractId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "契約の解約（期末解約の予約）",
            description = "有償契約は current_period_end までの利用を残したまま解約を予約する。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<BillingContractCancelResponse>> cancel(
            @PathVariable UUID contractId,
            @Valid @RequestBody BillingCancelRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        return idempotent(actorId, METHOD_CANCEL, contractId, idempotencyKey, request,
                () -> cancelApplicationService.cancel(actorId, contractId, request.version()));
    }

    /**
     * 解約予約を撤回する。
     *
     * @param contractId     対象契約
     * @param request        CAS 期待値（{@code version}）
     * @param idempotencyKey 冪等キー（必須・36文字以内）
     * @return 撤回後の状態
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "誤って入れた解約予約をGate状態にかかわらず取り消せるようにするため")
    @DeleteMapping("/me/billing/contracts/{contractId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "解約予約の撤回",
            description = "期末を跨ぐ前に限り、cancel_at_period_end を解除して契約を継続させる。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<BillingContractCancelResponse>> resume(
            @PathVariable UUID contractId,
            @Valid @RequestBody BillingCancelRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        return idempotent(actorId, METHOD_RESUME, contractId, idempotencyKey, request,
                () -> cancelApplicationService.resume(actorId, contractId, request.version()));
    }

    // ============================================================
    // 耐久冪等性（BillingCheckoutController と同一の流儀）
    // ============================================================

    private ResponseEntity<ApiResponse<BillingContractCancelResponse>> idempotent(
            Long actorId, String method, UUID contractId, String idempotencyKey,
            BillingCancelRequest request, Supplier<BillingContractCancelResponse> action) {

        String path = String.format(CANCEL_PATH_FORMAT, contractId);
        String requestHash = requestHash(actorId, method, path, request);
        String leaseOwner = UUID.randomUUID().toString();
        BillingIdempotencyDecision decision =
                idempotencyService.begin(actorId, method, path, idempotencyKey, requestHash, leaseOwner);

        if (decision.kind() == BillingIdempotencyDecisionKind.PROCESSING) {
            throw new BillingIdempotencyProcessingException(decision.retryAfterSeconds());
        }
        if (decision.kind() == BillingIdempotencyDecisionKind.REPLAY) {
            return replay(decision);
        }

        BillingContractCancelResponse body;
        try {
            body = action.get();
        } catch (RuntimeException e) {
            // 失敗は FAILED で確定させる（本文は保存しないので、同じキーの再送は 021/409・AC-31）。
            idempotencyService.fail(decision.id(), leaseOwner);
            throw e;
        }
        ApiResponse<BillingContractCancelResponse> envelope = ApiResponse.of(body);
        idempotencyService.complete(decision.id(), leaseOwner, HttpStatus.OK.value(),
                writeJson(envelope));
        return ResponseEntity.ok(envelope);
    }

    /** 保存済み応答をそのまま返す（本処理は再実行せず Stripe も呼ばない・AC-28 / AC-45）。 */
    private ResponseEntity<ApiResponse<BillingContractCancelResponse>> replay(
            BillingIdempotencyDecision decision) {
        if (decision.responseJson() == null || decision.responseStatus() == null) {
            // FAILED 確定済み（本文を保存していない）。失敗を再現せず新しいキーでの再送を促す（AC-31/AC-31b）。
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
        try {
            JsonNode data = objectMapper.readTree(decision.responseJson()).get(DATA_FIELD);
            if (data == null) {
                throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
            }
            return ResponseEntity.status(decision.responseStatus())
                    .body(ApiResponse.of(
                            objectMapper.treeToValue(data, BillingContractCancelResponse.class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to replay stored idempotent response", e);
        }
    }

    /** {@code BillingCheckoutController#requestHash} と同一の算式（区切りは改行）。 */
    private String requestHash(Long actorId, String method, String path, Object request) {
        String canonical = String.join("\n", String.valueOf(actorId), method, path, writeJson(request));
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
            throw new IllegalStateException("failed to serialize billing cancel payload", e);
        }
    }
}
