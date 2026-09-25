package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.BillingPriceProvisionRecoveryService;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.PriceRevisionCreateRequest;
import com.mannschaft.app.billing.api.dto.PriceRevisionListQuery;
import com.mannschaft.app.billing.api.dto.PriceRevisionLockVersionRequest;
import com.mannschaft.app.billing.api.dto.PriceRevisionPageResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 出陣隊（第3陣）D/I群: {@code /api/v1/system-admin/billing/price-revisions}（決定2・決定2b・決定9改訂）。
 *
 * <p>全 EP {@code SYSTEM_ADMIN} 限定（AC-43）。{@code @PreAuthorize} はメソッド単位で明示付与する
 * （{@link PriceRevisionAuthorizationAnnotationTest} がリフレクションで直接照合するため）。
 * {@code SecurityConfig} の {@code /api/v1/system-admin/**} パスルールと二重で担保する。</p>
 *
 * <p><b>冪等性（決定2b）</b>: create/provision/retry-provision/reconcile-provision/activate は
 * {@code Idempotency-Key} ヘッダ必須。{@link BillingDurableIdempotencyService} を
 * {@link BillingContractCancelResumeController} と同一の流儀で通す。
 * <b>業務上想定される4xx（バリデーション400・404・状態競合/CAS/overlap 409）は complete として保存し
 * 再送で再生する</b>。真に予期しない例外（5xx 相当）だけを fail() する。
 * provision/retry-provision/reconcile-provision の3 EP のみ lease 9分（決定9改訂・AC-132）、
 * それ以外（create/activate）は既定2分。</p>
 */
@RestController("priceRevisionController")
@RequestMapping("/api/v1/system-admin/billing/price-revisions")
@Tag(name = "システム管理 - 価格改定", description = "価格改定（price-revisions）: create/provision/retry/reconcile/activate/cancel/取得/一覧")
@RequiredArgsConstructor
public class PriceRevisionController {

    private static final Duration PROVISION_LEASE_DURATION = Duration.ofMinutes(9);
    private static final String BASE_PATH = "/api/v1/system-admin/billing/price-revisions";

    private final PriceRevisionCreateService createService;
    private final PriceRevisionQueryService queryService;
    private final PriceRevisionProvisionService provisionService;
    private final PriceRevisionRetryProvisionService retryProvisionService;
    private final BillingPriceProvisionRecoveryService reconcileService;
    private final PriceRevisionActivationService activationService;
    private final PriceRevisionCancelService cancelService;
    private final BillingDurableIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "価格改定 revision 作成", description = "DRAFT状態で作成する。Idempotency-Key 必須。")
    public ResponseEntity<Object> create(
            @Valid @RequestBody PriceRevisionCreateRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        return idempotent(actorId, "POST", BASE_PATH, idempotencyKey, request, HttpStatus.CREATED, null,
                () -> createService.create(request, actorId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "価格改定 revision 取得", description = "band明細を含む。不在・論理削除済みは404。")
    public ResponseEntity<ApiResponse<PriceRevisionResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(queryService.getById(id)));
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "価格改定 revision 一覧", description = "band明細を含まない要約。既定20件・上限100件。"
            + " ソートは effectiveFrom DESC, id DESC 固定。")
    public ResponseEntity<ApiResponse<PriceRevisionPageResponse>> list(
            @RequestParam(required = false) BillingProductKind productKind,
            @RequestParam(required = false) String productKey,
            @RequestParam(required = false) EntitlementScopeKind scopeKind,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        PriceRevisionListQuery query = new PriceRevisionListQuery(productKind, productKey, scopeKind, status, pageable);
        return ResponseEntity.ok(ApiResponse.of(queryService.list(query)));
    }

    @PostMapping("/{id}/provision")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "Provision実行", description = "DRAFT band を Stripe へ作成する。同期実行・fail-forward。Idempotency-Key 必須。")
    public ResponseEntity<Object> provision(
            @PathVariable UUID id,
            @RequestBody(required = false) PriceRevisionLockVersionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        PriceRevisionLockVersionRequest body = requestOrDefault(request);
        String path = BASE_PATH + "/" + id + "/provision";
        return idempotent(actorId, "POST", path, idempotencyKey, body, HttpStatus.OK, PROVISION_LEASE_DURATION,
                () -> provisionService.provision(id, body.lockVersion(), actorId));
    }

    @PostMapping("/{id}/retry-provision")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "Provision再試行", description = "PROVISION_FAILEDのbandのみ作り直す。READYは変更しない。Idempotency-Key 必須。")
    public ResponseEntity<Object> retryProvision(
            @PathVariable UUID id,
            @RequestBody(required = false) PriceRevisionLockVersionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        PriceRevisionLockVersionRequest body = requestOrDefault(request);
        String path = BASE_PATH + "/" + id + "/retry-provision";
        return idempotent(actorId, "POST", path, idempotencyKey, body, HttpStatus.OK, PROVISION_LEASE_DURATION,
                () -> retryProvisionService.retryProvision(id, body.lockVersion(), actorId));
    }

    @PostMapping("/{id}/reconcile-provision")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "Provision回収", description = "PROVISIONINGのまま停止したbandをStripe側属性と全照合しREADYへ回収するか隔離する。Idempotency-Key 必須。")
    public ResponseEntity<Object> reconcileProvision(
            @PathVariable UUID id,
            @RequestBody(required = false) PriceRevisionLockVersionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        PriceRevisionLockVersionRequest body = requestOrDefault(request);
        String path = BASE_PATH + "/" + id + "/reconcile-provision";
        return idempotent(actorId, "POST", path, idempotencyKey, body, HttpStatus.OK, PROVISION_LEASE_DURATION,
                () -> reconcileService.reconcileProvision(id, body.lockVersion(), actorId));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "Activate実行", description = "全band READY のときのみ成功する。即時ならACTIVE、未来ならSCHEDULEDへ。Idempotency-Key 必須。")
    public ResponseEntity<Object> activate(
            @PathVariable UUID id,
            @RequestBody(required = false) PriceRevisionLockVersionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        PriceRevisionLockVersionRequest body = requestOrDefault(request);
        String path = BASE_PATH + "/" + id + "/activate";
        return idempotent(actorId, "POST", path, idempotencyKey, body, HttpStatus.OK, null,
                () -> activationService.activate(id, body.lockVersion(), actorId));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "修復できない価格改定を取り消して商品の future 枠を解放する運用操作を、Gate状態にかかわらず可能にするため")
    @Operation(summary = "価格改定の取り消し", description = "DRAFT/READY/PROVISION_FAILED の revision と全 band を CANCELLED にし、"
            + "future 枠を解放する。それ以外の状態は409。Stripe 側の Price には触らない。Idempotency-Key 必須。")
    public ResponseEntity<Object> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) PriceRevisionLockVersionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36) String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        PriceRevisionLockVersionRequest body = requestOrDefault(request);
        String path = BASE_PATH + "/" + id + "/cancel";
        return idempotent(actorId, "POST", path, idempotencyKey, body, HttpStatus.OK, null,
                () -> cancelService.cancel(id, body.lockVersion(), actorId));
    }

    private PriceRevisionLockVersionRequest requestOrDefault(PriceRevisionLockVersionRequest request) {
        return request == null ? new PriceRevisionLockVersionRequest() : request;
    }

    // ============================================================
    // 耐久冪等性（BillingContractCancelResumeController と同一の流儀・決定2b）
    // ============================================================

    /**
     * @param leaseDuration null なら既定2分（{@link BillingDurableIdempotencyService} の既定オーバーロード）。
     *                      provision系3 EP のみ9分を明示する（AC-132）。
     */
    private ResponseEntity<Object> idempotent(
            Long actorId, String method, String path, String idempotencyKey, Object requestBody,
            HttpStatus successStatus, Duration leaseDuration, Supplier<?> action) {
        String requestHash = requestHash(actorId, method, path, requestBody);
        String leaseOwner = UUID.randomUUID().toString();
        BillingIdempotencyDecision decision = leaseDuration == null
                ? idempotencyService.begin(actorId, method, path, idempotencyKey, requestHash, leaseOwner)
                : idempotencyService.begin(actorId, method, path, idempotencyKey, requestHash, leaseOwner, leaseDuration);

        if (decision.kind() == BillingIdempotencyDecisionKind.PROCESSING) {
            throw new BillingIdempotencyProcessingException(decision.retryAfterSeconds());
        }
        if (decision.kind() == BillingIdempotencyDecisionKind.REPLAY) {
            return replay(decision);
        }

        Object result;
        try {
            result = action.get();
        } catch (BusinessException e) {
            // 決定2b: 業務上想定される4xx（バリデーション400・404・状態競合/CAS/overlap 409）は
            // complete として保存し再送で再生する。fail() は真に予期しない例外専用。
            HttpStatus status = e.getHttpStatusOverride() != null
                    ? e.getHttpStatusOverride()
                    : GlobalExceptionHandler.resolveStatus(e.getErrorCode());
            if (status.is4xxClientError()) {
                ErrorResponse errorBody = e.getFieldErrors().isEmpty()
                        ? ErrorResponse.of(e.getErrorCode())
                        : ErrorResponse.of(e.getErrorCode(), e.getFieldErrors());
                idempotencyService.complete(decision.id(), leaseOwner, status.value(), writeJson(errorBody));
            } else {
                idempotencyService.fail(decision.id(), leaseOwner);
            }
            throw e;
        } catch (RuntimeException e) {
            idempotencyService.fail(decision.id(), leaseOwner);
            throw e;
        }

        ApiResponse<Object> envelope = ApiResponse.of(result);
        idempotencyService.complete(decision.id(), leaseOwner, successStatus.value(), writeJson(envelope));
        return ResponseEntity.status(successStatus).body(envelope);
    }

    /** 保存済み応答をそのまま再生する（成功・業務4xxのいずれも本処理は再実行しない）。 */
    private ResponseEntity<Object> replay(BillingIdempotencyDecision decision) {
        if (decision.responseJson() == null || decision.responseStatus() == null) {
            // FAILED 確定済み（本文を保存していない・真に予期しない例外だった）。
            // 失敗を再現せず新しいキーでの再送を促す。
            throw new BillingIdempotencyProcessingException(0L);
        }
        try {
            JsonNode parsed = objectMapper.readTree(decision.responseJson());
            return ResponseEntity.status(decision.responseStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(parsed);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to replay stored idempotent response", e);
        }
    }

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
            throw new IllegalStateException("failed to serialize price-revision payload", e);
        }
    }
}
