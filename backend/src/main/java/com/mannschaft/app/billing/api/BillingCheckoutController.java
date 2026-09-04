package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.BillingQuoteResponse;
import com.mannschaft.app.billing.api.dto.CreateBillingCheckoutSessionRequest;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;
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
import java.util.function.Supplier;

/**
 * PR4 Billing Center: 見積り（quote）と Checkout Session の HTTP 入口（BC-03 / BC-13 / BC-23）。
 *
 * <p><b>認可</b>: 入口は認証必須（{@code isAuthenticated()}）に留め、実際の scope 認可は
 * application service が {@link BillingCheckoutScopeGuard} 経由で
 * {@link BillingAccessGuard#canManageByActorId} に委ねる。scope はパスではなく
 * <b>リクエスト本文</b>（quote）と <b>quote に焼き付いた値</b>（checkout）から来るため、
 * SpEL のパス引数では守れず、必ずサービス層で actor と突き合わせる必要がある
 * （checkout は quote の所有者一致も併せて検証し、他人の quote は失効と同じ 409 に畳む）。</p>
 *
 * <p><b>冪等性（BC-23）</b>: どちらも {@code Idempotency-Key} ヘッダ必須（欠落時は Spring が
 * {@code MissingRequestHeaderException} → 400）。ヘッダを受け取るだけでは二重実行は塞げないため、
 * 両入口とも {@link BillingDurableIdempotencyService} の {@code begin}/{@code complete} を必ず通す。
 * begin の 3 分岐は HTTP 応答へ次のとおり写す。</p>
 * <ul>
 *   <li>REPLAY — 保存済みの status と body をそのまま返す（サービスへは到達しない）。
 *       保存済み本文が無い（＝先行要求が FAILED で確定した）場合は 021/409 とし、
 *       利用者には新しいキーでの再送を求める（失敗の再現ではなく再実行の抑止が目的）。</li>
 *   <li>PROCESSING — {@link BillingIdempotencyProcessingException} で 409 ＋ {@code Retry-After}。</li>
 *   <li>ACQUIRED — 本処理を実行し、成功時に status と body を耐久化（complete）する。
 *       失敗時は FAILED へ確定させてから元の例外をそのまま送出する（症状を隠さない）。</li>
 * </ul>
 * <p>Stripe 側の二重 Session 作成は、これに加えて application service が渡す
 * Stripe idempotency key で塞ぐ（二段構え）。</p>
 *
 * <p><b>トランザクション</b>: 本 controller は {@code @Transactional} を持たない。冪等レコードの
 * 予約（PROCESSING）→ commit → 外部 Stripe → CAS 確定、という設計順序を成立させるため、
 * 境界は各ポート実装（アダプタ）側の短いトランザクションに置いている。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "課金 - 見積り/Checkout", description = "PR4 quote 発行と Stripe Checkout Session 作成")
@RequiredArgsConstructor
public class BillingCheckoutController {

    /** 冪等レコードの request path（{@code uk_bai_actor_request} の一部）。 */
    static final String QUOTE_PATH = "/api/v1/me/billing/quotes";
    static final String CHECKOUT_PATH = BillingCheckoutApplicationService.IDEMPOTENCY_PATH;
    private static final String METHOD = "POST";
    private static final String DATA_FIELD = "data";

    private final BillingQuoteService quoteService;
    private final BillingCheckoutApplicationService checkoutApplicationService;
    private final BillingDurableIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    /**
     * 見積り（quote）を発行する。有効期間は 10 分で、Checkout 直前に再照合される。
     *
     * @param request      対象 scope と商品
     * @param idempotencyKey 冪等キー（必須）
     * @return 発行した quote
     */
    @PostMapping("/me/billing/quotes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "見積り発行",
            description = "scope は本文で指定し、操作者の課金管理権限をサービス層で検証する。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<BillingQuoteResponse>> createQuote(
            @Valid @RequestBody CreateBillingQuoteRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        return idempotent(actorId, QUOTE_PATH, idempotencyKey, request, BillingQuoteResponse.class,
                () -> quoteService.create(actorId, request, idempotencyKey));
    }

    /**
     * quote を消費して Stripe Checkout Session を作成する。
     *
     * @param request        消費する quote
     * @param idempotencyKey 冪等キー（必須・Stripe への再送も同一キーに束縛する）
     * @return Checkout URL と Session 失効時刻
     */
    @PostMapping("/me/billing/checkout-sessions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Checkout Session 作成",
            description = "quote の所有者・scope・価格・月境界を再検証してから Stripe Checkout を作成する。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<BillingCheckoutApplicationService.CheckoutSessionResponse>>
            createCheckoutSession(
                    @Valid @RequestBody CreateBillingCheckoutSessionRequest request,
                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        return idempotent(actorId, CHECKOUT_PATH, idempotencyKey, request,
                BillingCheckoutApplicationService.CheckoutSessionResponse.class,
                () -> checkoutApplicationService.create(actorId, request, idempotencyKey));
    }

    /**
     * 耐久冪等性で本処理を包む（BC-23）。
     *
     * @param responseType replay 時に保存済み body を復元する型
     * @param action       ACQUIRED のときだけ実行する本処理
     */
    private <T> ResponseEntity<ApiResponse<T>> idempotent(
            Long actorId, String path, String idempotencyKey, Object request,
            Class<T> responseType, Supplier<T> action) {
        String requestHash = requestHash(actorId, path, request);
        String leaseOwner = UUID.randomUUID().toString();
        BillingIdempotencyDecision decision =
                idempotencyService.begin(actorId, METHOD, path, idempotencyKey, requestHash, leaseOwner);

        if (decision.kind() == BillingIdempotencyDecisionKind.PROCESSING) {
            throw new BillingIdempotencyProcessingException(decision.retryAfterSeconds());
        }
        if (decision.kind() == BillingIdempotencyDecisionKind.REPLAY) {
            return replay(decision, responseType);
        }

        T body;
        try {
            body = action.get();
        } catch (RuntimeException e) {
            idempotencyService.fail(decision.id(), leaseOwner);
            throw e;
        }
        ApiResponse<T> envelope = ApiResponse.of(body);
        idempotencyService.complete(decision.id(), leaseOwner, HttpStatus.CREATED.value(),
                writeJson(envelope));
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope);
    }

    /** 保存済み応答をそのまま返す（本処理は再実行しない）。 */
    private <T> ResponseEntity<ApiResponse<T>> replay(BillingIdempotencyDecision decision,
                                                      Class<T> responseType) {
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
                    .body(ApiResponse.of(objectMapper.treeToValue(data, responseType)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to replay stored idempotent response", e);
        }
    }

    /**
     * request hash（SHA-256 hex 64 文字）。
     *
     * <p>「同じキーで違う中身」を検出するため、<b>actor / HTTP method / request path /
     * リクエスト本文の JSON 表現</b> を区切り付きで連結して取る（DTO は record であり
     * Jackson の出力順は宣言順で決まるため、同一入力に対して安定する）。
     * 冪等キーそのものは束縛の一部（UNIQUE キー側）であってハッシュ対象には含めない。</p>
     */
    private String requestHash(Long actorId, String path, Object request) {
        String canonical = String.join("\n", String.valueOf(actorId), METHOD, path, writeJson(request));
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
            throw new IllegalStateException("failed to serialize billing checkout payload", e);
        }
    }
}
