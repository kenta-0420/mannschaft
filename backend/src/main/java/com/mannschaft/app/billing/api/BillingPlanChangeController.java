package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.BillingChangePreviewRequest;
import com.mannschaft.app.billing.api.dto.BillingChangePreviewResponse;
import com.mannschaft.app.billing.api.dto.BillingContractChangeResponse;
import com.mannschaft.app.billing.api.dto.BillingPlanChangeRequest;
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
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Billing Center PR6b-1: PLAN 変更（upgrade）の HTTP 入口（正本 05_billing_center.md:380-381）。
 *
 * <pre>
 * POST /api/v1/me/billing/contracts/{contractId}/change-previews   事前見積り（A群 AC-1〜24）
 * POST /api/v1/me/billing/contracts/{contractId}/changes           変更の実行（B群の走り）
 * </pre>
 *
 * <p>本 controller が担うのは「認可済みの actor から具体的な HTTP リクエストを受け取り、
 * サービスへ委譲して応答へ組み立てる」ことだけである。認可・再照合・CAS・Saga への委譲は
 * {@link BillingPlanChangePreviewService} / {@link BillingPlanChangeService} が持つ。</p>
 *
 * <p><b>冪等性</b>: 両エンドポイントとも {@code Idempotency-Key} ヘッダを必須にする（正本どおり）。
 * A群（本 PR6b-1 の担当範囲）は preview の一回消費 CAS が実質的な冪等性の担保であるため、
 * {@link BillingContractCancelResumeController} のような耐久冪等台帳（begin/complete）はまだ持たない
 * ——同一キーの再送で完全な replay を返す精緻化は AC-47（B群）として第7隊が引き継ぐ。</p>
 */
@RestController
@RequestMapping("/api/v1/me/billing/contracts")
@Tag(name = "課金 - プラン変更", description = "Billing Center PR6b-1 事前見積り・upgrade 実行")
@RequiredArgsConstructor
public class BillingPlanChangeController {

    private static final String CHANGES_PATH_FORMAT = "/api/v1/me/billing/contracts/%s/changes";
    private static final String METHOD_CHANGE = "POST";
    private static final String DATA_FIELD = "data";

    private final BillingPlanChangePreviewService previewService;
    private final BillingPlanChangeService changeService;
    /** AC-47: 同一 Idempotency-Key の再送は同一レスポンスを返し Stripe 呼び出しは1回にする。 */
    private final BillingDurableIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    /**
     * 事前見積り（AC-1〜24）。
     *
     * @param contractId     対象契約
     * @param request        見積り要求
     * @param idempotencyKey 冪等キー（必須・36文字以内）
     * @return 見積り
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の既存課金契約のプラン変更見積りをGate状態にかかわらず取得可能にするため")
    @PostMapping("/{contractId}/change-previews")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "PLAN 変更の事前見積り",
            description = "upgrade 対象の金額・期間を Stripe から取得する。Idempotency-Key 必須。")
    @SneakyThrows
    public ResponseEntity<ApiResponse<BillingChangePreviewResponse>> preview(
            @PathVariable UUID contractId,
            @Valid @RequestBody BillingChangePreviewRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        long actorId = SecurityUtils.getCurrentUserId();
        String requestBody = objectMapper.writeValueAsString(request);
        BillingChangePreviewResponse response = previewService.preview(actorId, contractId, request, requestBody);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 変更の実行（B群の走り）。
     *
     * @param contractId     対象契約
     * @param request        previewId と CAS 期待値
     * @param idempotencyKey 冪等キー（必須・36文字以内）
     * @return 起票された変更
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の既存課金契約のプラン変更をGate状態にかかわらず実行可能にするため")
    @PostMapping("/{contractId}/changes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "PLAN 変更（upgrade）の実行",
            description = "事前見積りを一回だけ消費して upgrade を予約する。Idempotency-Key 必須。"
                    + " clientSecret は返さない。")
    @SneakyThrows
    public ResponseEntity<ApiResponse<BillingContractChangeResponse>> change(
            @PathVariable UUID contractId,
            @Valid @RequestBody BillingPlanChangeRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        long actorId = SecurityUtils.getCurrentUserId();
        String path = String.format(CHANGES_PATH_FORMAT, contractId);
        String requestHash = requestHash(actorId, METHOD_CHANGE, path, request);
        String leaseOwner = UUID.randomUUID().toString();
        BillingIdempotencyDecision decision =
                idempotencyService.begin(actorId, METHOD_CHANGE, path, idempotencyKey, requestHash, leaseOwner);

        if (decision.kind() == BillingIdempotencyDecisionKind.PROCESSING) {
            throw new BillingIdempotencyProcessingException(decision.retryAfterSeconds());
        }
        if (decision.kind() == BillingIdempotencyDecisionKind.REPLAY) {
            return replay(decision);
        }

        String requestBody = objectMapper.writeValueAsString(request);
        BillingContractChangeResponse body;
        try {
            body = changeService.change(actorId, contractId, request, idempotencyKey, requestBody);
        } catch (RuntimeException e) {
            // 失敗は FAILED で確定させる（本文は保存しないので、同じキーの再送は 021/409）。
            idempotencyService.fail(decision.id(), leaseOwner);
            throw e;
        }
        ApiResponse<BillingContractChangeResponse> envelope = ApiResponse.of(body);
        idempotencyService.complete(decision.id(), leaseOwner, HttpStatus.ACCEPTED.value(), writeJson(envelope));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(envelope);
    }

    /** 保存済み応答をそのまま返す（本処理は再実行せず Stripe も呼ばない・AC-47）。 */
    private ResponseEntity<ApiResponse<BillingContractChangeResponse>> replay(
            BillingIdempotencyDecision decision) {
        if (decision.responseJson() == null || decision.responseStatus() == null) {
            // FAILED 確定済み（本文を保存していない）。失敗を再現せず新しいキーでの再送を促す。
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
        try {
            JsonNode data = objectMapper.readTree(decision.responseJson()).get(DATA_FIELD);
            if (data == null) {
                throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
            }
            return ResponseEntity.status(decision.responseStatus())
                    .body(ApiResponse.of(
                            objectMapper.treeToValue(data, BillingContractChangeResponse.class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to replay stored idempotent response", e);
        }
    }

    /** {@code BillingContractCancelResumeController#requestHash} と同一の算式（区切りは改行）。 */
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
            throw new IllegalStateException("failed to serialize billing plan change payload", e);
        }
    }
}
