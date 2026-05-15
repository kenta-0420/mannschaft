package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.pointcard.dto.CreateOrgProviderRequest;
import com.mannschaft.app.pointcard.dto.CustomerQrResponse;
import com.mannschaft.app.pointcard.dto.PointCardProviderResponse;
import com.mannschaft.app.pointcard.dto.UpdateOrgProviderRequest;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OrgPointCardProviderService} 単体テスト（F18 Phase 2 S2B）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>createOrgProvider: 正常系 / type=SELF_ISSUED_STAMP / code 自動生成 /
 *       20 個上限 (010) / 監査ログ / イベント発火</li>
 *   <li>updateOrgProvider: 正常系 / IDOR (011) / 監査ログ / イベント発火</li>
 *   <li>deactivateOrgProvider: ADMIN のみ / DEPUTY_ADMIN 拒否 / IDOR / イベント発火</li>
 *   <li>getCustomerQr: deepLinkUrl と webUrl の組み立て</li>
 *   <li>listOrgProviders: activeOnly フラグの分岐</li>
 *   <li>権限不足 (checkAdminOrAbove 経由) の伝搬</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrgPointCardProviderService 単体テスト")
class OrgPointCardProviderServiceTest {

    private static final Long ORG_ID = 42L;
    private static final Long OTHER_ORG_ID = 99L;
    private static final Long USER_ADMIN = 100L;
    private static final Long USER_DEPUTY = 101L;
    private static final Long USER_MEMBER = 200L;
    private static final String SCOPE = "ORGANIZATION";

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrgPointCardProviderService service;

    @BeforeEach
    void setUp() {
        // @Value の既定値を反射で注入（@InjectMocks は @Value を解決しないため）
        ReflectionTestUtils.setField(service, "deepLinkBase",
                "mannschaft://wallet/add-from-qr");
        ReflectionTestUtils.setField(service, "webBase",
                "https://mannschaft.example.com/wallet/add-from-qr");
    }

    // ─────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────

    private PointCardProviderEntity sampleSelfIssuedProvider(Long orgId) {
        PointCardProviderEntity p = PointCardProviderEntity.builder()
                .code("org_" + orgId + "_12345678")
                .displayName("サロン○○ ポイント")
                .category(PointCardCategory.OTHER)
                .type(PointCardProviderType.SELF_ISSUED_STAMP)
                .organizationId(orgId)
                .brandColor("#FF6699")
                .active(Boolean.TRUE)
                .build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private CreateOrgProviderRequest sampleCreateRequest() {
        return new CreateOrgProviderRequest(
                "サロン○○ ポイント",
                "#FF6699",
                "https://r2.example.com/logos/salon.png",
                "^[0-9]{8}$",
                "8 桁の数字"
        );
    }

    // ─────────────────────────────────────────────
    // createOrgProvider
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("createOrgProvider: 正常系 — type=SELF_ISSUED_STAMP / code=org_*_rand8 / 監査ログ + イベント発火")
    void createOrgProvider_success() {
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        given(providerRepository.countByOrganizationIdAndActiveTrue(ORG_ID)).willReturn(5L);
        given(providerRepository.save(any(PointCardProviderEntity.class)))
                .willAnswer(inv -> {
                    PointCardProviderEntity e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(UUID.randomUUID());
                    return e;
                });

        PointCardProviderResponse res = service.createOrgProvider(
                ORG_ID, USER_ADMIN, sampleCreateRequest());

        // 認可検証が呼ばれる
        verify(accessControlService).checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);

        // 保存内容を検証
        ArgumentCaptor<PointCardProviderEntity> entityCaptor =
                ArgumentCaptor.forClass(PointCardProviderEntity.class);
        verify(providerRepository).save(entityCaptor.capture());
        PointCardProviderEntity saved = entityCaptor.getValue();
        assertThat(saved.getType()).isEqualTo(PointCardProviderType.SELF_ISSUED_STAMP);
        assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(saved.getCode()).startsWith("org_" + ORG_ID + "_");
        assertThat(saved.getCode().length()).isEqualTo(("org_" + ORG_ID + "_").length() + 8);
        assertThat(saved.getDisplayName()).isEqualTo("サロン○○ ポイント");
        assertThat(saved.getBrandColor()).isEqualTo("#FF6699");
        assertThat(saved.getActive()).isEqualTo(Boolean.TRUE);

        // 監査ログ
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_PROVIDER_CREATED.name()),
                eq(USER_ADMIN),
                any(), any(), eq(ORG_ID),
                any(), any(), any(),
                metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .contains("\"organization_id\":" + ORG_ID)
                .contains("\"display_name\":\"サロン○○ ポイント\"");

        // キャッシュリフレッシュイベント
        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));

        // レスポンス
        assertThat(res.type()).isEqualTo(PointCardProviderType.SELF_ISSUED_STAMP);
        assertThat(res.organizationId()).isEqualTo(ORG_ID);
    }

    @Test
    @DisplayName("createOrgProvider: 20 個上限超過 → PROVIDER_LIMIT_EXCEEDED (010)")
    void createOrgProvider_limitExceeded() {
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        given(providerRepository.countByOrganizationIdAndActiveTrue(ORG_ID)).willReturn(20L);

        assertThatThrownBy(() -> service.createOrgProvider(
                ORG_ID, USER_ADMIN, sampleCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_LIMIT_EXCEEDED);

        verify(providerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("createOrgProvider: cardNumberRegex の構文不正 → COMMON_001 (400)")
    void createOrgProvider_invalidRegex() {
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        CreateOrgProviderRequest bad = new CreateOrgProviderRequest(
                "test", null, null, "[invalid(", null);

        assertThatThrownBy(() -> service.createOrgProvider(ORG_ID, USER_ADMIN, bad))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_001);

        verify(providerRepository, never()).save(any());
    }

    @Test
    @DisplayName("createOrgProvider: 権限不足は AccessControlService から例外伝搬")
    void createOrgProvider_forbidden() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkAdminOrAbove(USER_MEMBER, ORG_ID, SCOPE);

        assertThatThrownBy(() -> service.createOrgProvider(
                ORG_ID, USER_MEMBER, sampleCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(providerRepository, never()).save(any());
    }

    @Test
    @DisplayName("createOrgProvider: DEPUTY_ADMIN でも checkAdminOrAbove を通れば作成できる")
    void createOrgProvider_deputyAdminAllowed() {
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_DEPUTY, ORG_ID, SCOPE);
        given(providerRepository.countByOrganizationIdAndActiveTrue(ORG_ID)).willReturn(0L);
        given(providerRepository.save(any(PointCardProviderEntity.class)))
                .willAnswer(inv -> {
                    PointCardProviderEntity e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(UUID.randomUUID());
                    return e;
                });

        PointCardProviderResponse res = service.createOrgProvider(
                ORG_ID, USER_DEPUTY, sampleCreateRequest());

        assertThat(res).isNotNull();
        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    // ─────────────────────────────────────────────
    // updateOrgProvider
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("updateOrgProvider: 正常系 — 差分適用 + 監査ログ + イベント発火")
    void updateOrgProvider_success() {
        PointCardProviderEntity existing = sampleSelfIssuedProvider(ORG_ID);
        UUID providerId = existing.getId();

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        given(providerRepository.findById(providerId)).willReturn(Optional.of(existing));
        given(providerRepository.save(any(PointCardProviderEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateOrgProviderRequest req = new UpdateOrgProviderRequest(
                "新しい名前", "#00FF00", null, null, "10 桁");
        PointCardProviderResponse res = service.updateOrgProvider(
                ORG_ID, providerId, USER_ADMIN, req);

        assertThat(res.displayName()).isEqualTo("新しい名前");
        // 不変項目は維持される
        assertThat(existing.getType()).isEqualTo(PointCardProviderType.SELF_ISSUED_STAMP);
        assertThat(existing.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(existing.getCode()).startsWith("org_" + ORG_ID + "_");
        assertThat(existing.getBrandColor()).isEqualTo("#00FF00");

        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_PROVIDER_UPDATED.name()),
                eq(USER_ADMIN), any(), any(), eq(ORG_ID),
                any(), any(), any(), anyString());
        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("updateOrgProvider: 他組織の provider への PATCH は PROVIDER_NOT_OWNED (011)")
    void updateOrgProvider_idor() {
        PointCardProviderEntity foreign = sampleSelfIssuedProvider(OTHER_ORG_ID);
        UUID providerId = foreign.getId();

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        given(providerRepository.findById(providerId)).willReturn(Optional.of(foreign));

        UpdateOrgProviderRequest req = new UpdateOrgProviderRequest(
                "侵入", null, null, null, null);
        assertThatThrownBy(() -> service.updateOrgProvider(
                ORG_ID, providerId, USER_ADMIN, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_OWNED);

        verify(providerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("updateOrgProvider: provider 存在しない場合も PROVIDER_NOT_OWNED (秘匿)")
    void updateOrgProvider_notFound() {
        UUID providerId = UUID.randomUUID();
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        given(providerRepository.findById(providerId)).willReturn(Optional.empty());

        UpdateOrgProviderRequest req = new UpdateOrgProviderRequest(
                "あ", null, null, null, null);
        assertThatThrownBy(() -> service.updateOrgProvider(
                ORG_ID, providerId, USER_ADMIN, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_OWNED);
    }

    // ─────────────────────────────────────────────
    // deactivateOrgProvider — ADMIN only
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("deactivateOrgProvider: ADMIN は is_active=false に更新 + 監査ログ + イベント発火")
    void deactivate_admin_success() {
        PointCardProviderEntity existing = sampleSelfIssuedProvider(ORG_ID);
        UUID providerId = existing.getId();

        given(accessControlService.isAdmin(USER_ADMIN, ORG_ID, SCOPE)).willReturn(true);
        given(providerRepository.findById(providerId)).willReturn(Optional.of(existing));
        given(providerRepository.save(any(PointCardProviderEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.deactivateOrgProvider(ORG_ID, providerId, USER_ADMIN);

        assertThat(existing.getActive()).isEqualTo(Boolean.FALSE);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_PROVIDER_DEACTIVATED.name()),
                eq(USER_ADMIN), any(), any(), eq(ORG_ID),
                any(), any(), any(), anyString());
        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("deactivateOrgProvider: DEPUTY_ADMIN は拒否（isAdmin=false の場合 COMMON_002）")
    void deactivate_deputyForbidden() {
        UUID providerId = UUID.randomUUID();
        given(accessControlService.isAdmin(USER_DEPUTY, ORG_ID, SCOPE)).willReturn(false);

        assertThatThrownBy(() -> service.deactivateOrgProvider(ORG_ID, providerId, USER_DEPUTY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(providerRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("deactivateOrgProvider: 他組織の provider は PROVIDER_NOT_OWNED (IDOR)")
    void deactivate_idor() {
        PointCardProviderEntity foreign = sampleSelfIssuedProvider(OTHER_ORG_ID);
        UUID providerId = foreign.getId();

        given(accessControlService.isAdmin(USER_ADMIN, ORG_ID, SCOPE)).willReturn(true);
        given(providerRepository.findById(providerId)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.deactivateOrgProvider(ORG_ID, providerId, USER_ADMIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_OWNED);

        verify(providerRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────
    // getCustomerQr
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getCustomerQr: deepLinkUrl と webUrl が providerId 付きで組み立てられる")
    void getCustomerQr_buildsUrls() {
        PointCardProviderEntity existing = sampleSelfIssuedProvider(ORG_ID);
        UUID providerId = existing.getId();

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        given(providerRepository.findById(providerId)).willReturn(Optional.of(existing));

        CustomerQrResponse qr = service.getCustomerQr(ORG_ID, providerId, USER_ADMIN);

        assertThat(qr.providerId()).isEqualTo(providerId);
        assertThat(qr.displayName()).isEqualTo(existing.getDisplayName());
        assertThat(qr.deepLinkUrl())
                .isEqualTo("mannschaft://wallet/add-from-qr?providerId=" + providerId);
        assertThat(qr.webUrl())
                .isEqualTo("https://mannschaft.example.com/wallet/add-from-qr?providerId=" + providerId);
    }

    // ─────────────────────────────────────────────
    // listOrgProviders
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("listOrgProviders: activeOnly=true なら有効プロバイダーのみのクエリを呼ぶ")
    void listOrgProviders_activeOnly() {
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        given(providerRepository.findAllByOrganizationIdAndActiveTrue(ORG_ID))
                .willReturn(List.of(sampleSelfIssuedProvider(ORG_ID)));

        List<PointCardProviderResponse> result =
                service.listOrgProviders(ORG_ID, USER_ADMIN, true);

        assertThat(result).hasSize(1);
        verify(providerRepository).findAllByOrganizationIdAndActiveTrue(ORG_ID);
        verify(providerRepository, never())
                .findAllByOrganizationIdOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("listOrgProviders: activeOnly=false なら停止済も含めた全件クエリを呼ぶ")
    void listOrgProviders_all() {
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(USER_ADMIN, ORG_ID, SCOPE);
        PointCardProviderEntity active = sampleSelfIssuedProvider(ORG_ID);
        PointCardProviderEntity inactive = sampleSelfIssuedProvider(ORG_ID);
        inactive.setActive(Boolean.FALSE);
        given(providerRepository.findAllByOrganizationIdOrderByCreatedAtDesc(ORG_ID))
                .willReturn(List.of(active, inactive));

        List<PointCardProviderResponse> result =
                service.listOrgProviders(ORG_ID, USER_ADMIN, false);

        assertThat(result).hasSize(2);
        verify(providerRepository).findAllByOrganizationIdOrderByCreatedAtDesc(ORG_ID);
        verify(providerRepository, never()).findAllByOrganizationIdAndActiveTrue(any());
    }
}
