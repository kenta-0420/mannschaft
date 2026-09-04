package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.BillingCustomerPortalSessionResponse;
import com.mannschaft.app.billing.api.dto.CreateBillingCustomerPortalSessionRequest;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR5 Portal セッション発行の <b>振る舞い</b>テスト（AC-61 / AC-63 / AC-71 / AC-72 / AC-74）。
 *
 * <p>{@link BillingPortalSessionContractTrialTest} が「実コードがそう書かれているか」を測るのに対し、
 * 本テストは port を差した実行で「そう動くか」を測る。Stripe には一切触れない
 * （gateway 自体は {@link BillingCustomerPortalStripeGatewayTest} が測る）。</p>
 */
@DisplayName("PR5 Portal セッション発行の振る舞い")
class BillingCustomerPortalApplicationServiceTest {

    private static final long ACTOR_ID = 4_001L;
    private static final long OTHER_SCOPE_ID = 4_002L;
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-7000-8000-000000000401");
    private static final String CUSTOMER_REF = "cus_test_401";
    private static final String KEY = "key-401";

    /** 呼び出し順を 1 本の列に記録し、AC-74 の「順序」を実行で観測する。 */
    private final List<String> calls = new ArrayList<>();

    private RecordingAuditLogService auditLogService;
    private StubRateLimiter rateLimiter;
    private StubGateway gateway;
    private Optional<BillingCustomerPortalCustomer> customer;
    private BillingCustomerPortalApplicationService service;

    @BeforeEach
    void setUp() {
        calls.clear();
        auditLogService = new RecordingAuditLogService();
        rateLimiter = new StubRateLimiter(Integer.MAX_VALUE);
        gateway = new StubGateway();
        customer = Optional.of(new BillingCustomerPortalCustomer(
                CUSTOMER_ID, EntitlementScopeKind.USER, ACTOR_ID, CUSTOMER_REF));
        service = newService();
    }

    private BillingCustomerPortalApplicationService newService() {
        BillingCustomerPortalAccessGuard guard = (actorId, scopeKind, scopeId) -> {
            calls.add("guard");
            if (!(scopeKind == EntitlementScopeKind.USER && actorId == scopeId)) {
                throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
            }
        };
        BillingCustomerPortalCustomerRepository repository = (scopeKind, scopeId) -> {
            calls.add("customer");
            return customer;
        };
        return new BillingCustomerPortalApplicationService(
                guard, repository, rateLimiter, gateway, auditLogService);
    }

    private static CreateBillingCustomerPortalSessionRequest request(long scopeId) {
        return new CreateBillingCustomerPortalSessionRequest(EntitlementScopeKind.USER, scopeId);
    }

    @Test
    @DisplayName("AC61_他 scope への発行は 403（ENTITLEMENT_005）で、Stripe gateway を呼ばない")
    void AC61_他scopeは403でgatewayを呼ばない() {
        assertThatThrownBy(() -> service.create(ACTOR_ID, request(OTHER_SCOPE_ID), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.SCOPE_FORBIDDEN);
        assertThat(gateway.invocations).isZero();
        assertThat(auditLogService.eventTypes).isEmpty();
    }

    @Test
    @DisplayName("AC63_Customer が ACTIVE でなければ 409（ENTITLEMENT_024）で、Stripe gateway を呼ばない")
    void AC63_ACTIVE以外は409でgatewayを呼ばない() {
        customer = Optional.empty();
        assertThatThrownBy(() -> service.create(ACTOR_ID, request(ACTOR_ID), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.MIGRATION_REQUIRED);
        assertThat(gateway.invocations).isZero();
    }

    @Test
    @DisplayName("AC63_psp_customer_ref が無い ACTIVE 行でも Portal を開始しない")
    void AC63_customerRef不在も409() {
        customer = Optional.of(new BillingCustomerPortalCustomer(
                CUSTOMER_ID, EntitlementScopeKind.USER, ACTOR_ID, null));
        assertThatThrownBy(() -> service.create(ACTOR_ID, request(ACTOR_ID), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.MIGRATION_REQUIRED);
        assertThat(gateway.invocations).isZero();
    }

    @Test
    @DisplayName("AC71_scope ごと 10 回目までは成功し、11 回目は 429（ENTITLEMENT_028）")
    void AC71_10回目は成功し11回目は429() {
        rateLimiter = new StubRateLimiter(BillingCustomerPortalRateLimiterAdapter.LIMIT_PER_WINDOW);
        service = newService();

        for (int i = 1; i <= BillingCustomerPortalRateLimiterAdapter.LIMIT_PER_WINDOW; i++) {
            assertThat(service.create(ACTOR_ID, request(ACTOR_ID), KEY)).isNotNull();
        }
        assertThatThrownBy(() -> service.create(ACTOR_ID, request(ACTOR_ID), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.PORTAL_RATE_LIMITED);
        assertThat(gateway.invocations)
                .as("上限超過分は Stripe まで到達しない")
                .isEqualTo(BillingCustomerPortalRateLimiterAdapter.LIMIT_PER_WINDOW);
    }

    @Test
    @DisplayName("AC72_BILLING_PORTAL_OPENED を記録し、監査 metadata に URL を含めない")
    void AC72_監査にURLを含めない() {
        BillingCustomerPortalSessionResponse response = service.create(ACTOR_ID, request(ACTOR_ID), KEY);

        assertThat(response.url()).isEqualTo(StubGateway.PORTAL_URL);
        assertThat(auditLogService.eventTypes)
                .containsExactly(AuditEventType.BILLING_PORTAL_OPENED.name());
        assertThat(auditLogService.metadata).hasSize(1);
        String metadata = auditLogService.metadata.get(0);
        assertThat(metadata)
                .as("監査 metadata に Portal URL を載せてはならない（正本 §370）")
                .doesNotContain(StubGateway.PORTAL_URL)
                .doesNotContain("http")
                .contains("billingCustomerId");
    }

    @Test
    @DisplayName("AC72_Stripe 側が失敗したときは監査を残さない（開いていないため）")
    void AC72_失敗時は監査しない() {
        gateway.failure = new BusinessException(EntitlementErrorCode.STRIPE_UNAVAILABLE);
        assertThatThrownBy(() -> service.create(ACTOR_ID, request(ACTOR_ID), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.STRIPE_UNAVAILABLE);
        assertThat(auditLogService.eventTypes).isEmpty();
    }

    @Test
    @DisplayName("AC74_順序は 認可 → Customer 照合 → 回数制限 → Stripe。外部 I/O は最後に置く")
    void AC74_外部IOは最後() {
        service.create(ACTOR_ID, request(ACTOR_ID), KEY);
        assertThat(calls).containsExactly("guard", "customer", "rate-limit", "gateway");
    }

    @Test
    @DisplayName("AC70_呼び出し元の冪等キーが Stripe 側のキーへ束縛される")
    void AC70_冪等キーがStripeへ束縛される() {
        service.create(ACTOR_ID, request(ACTOR_ID), KEY);
        assertThat(gateway.lastRequest.stripeIdempotencyKey()).contains(KEY);
    }

    /** 回数制限の port スタブ（許可回数を数えるだけ。Valkey には触れない）。 */
    private final class StubRateLimiter implements BillingCustomerPortalRateLimiter {
        private final int limit;
        private int consumed;

        private StubRateLimiter(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean tryConsume(EntitlementScopeKind scopeKind, long scopeId) {
            calls.add("rate-limit");
            consumed++;
            return consumed <= limit;
        }
    }

    /** Stripe gateway の port スタブ。実 Stripe API は一切呼ばない。 */
    private final class StubGateway implements BillingCustomerPortalGateway {
        private static final String PORTAL_URL = "https://billing.stripe.com/session/test";

        private int invocations;
        private BillingCustomerPortalRequest lastRequest;
        private RuntimeException failure;

        @Override
        public BillingCustomerPortalResult createSession(BillingCustomerPortalRequest request) {
            calls.add("gateway");
            invocations++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return new BillingCustomerPortalResult(PORTAL_URL, Instant.parse("2026-09-05T00:00:00Z"));
        }
    }

    /** 監査呼び出しを記録するだけの {@link AuditLogService}（DB へは書かない）。 */
    private static final class RecordingAuditLogService extends AuditLogService {
        private final List<String> eventTypes = new ArrayList<>();
        private final List<String> metadata = new ArrayList<>();

        private RecordingAuditLogService() {
            super(null, null);
        }

        @Override
        public void record(String eventType, Long userId, Long targetUserId, Long teamId,
                           Long organizationId, String ipAddress, String userAgent,
                           String sessionHash, String metadataJson) {
            eventTypes.add(eventType);
            metadata.add(metadataJson);
        }
    }
}
