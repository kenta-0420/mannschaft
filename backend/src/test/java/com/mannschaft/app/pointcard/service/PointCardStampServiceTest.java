package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.pointcard.dto.StampEventResponse;
import com.mannschaft.app.pointcard.dto.StampRequest;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardStampEventEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.PointCardStampEventRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PointCardStampService} の単体テスト（F18 Phase 2 第二陣 2C）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2 / §12
 *
 * <p>カバー観点:
 * <ul>
 *   <li>stamp 正常系（+1 / +5 / -1）</li>
 *   <li>下限 0 ガード（{@code stamp_count=0} で {@code delta=-1} → 0 で止まる）</li>
 *   <li>provider_id=null で {@code STAMP_INVALID_PROVIDER}</li>
 *   <li>他組織のカードで {@code PROVIDER_NOT_FOUND}（IDOR 防止）</li>
 *   <li>非 STAMP type で {@code STAMP_INVALID_PROVIDER_TYPE}</li>
 *   <li>非 active で {@code PROVIDER_NOT_FOUND}</li>
 *   <li>delta=0 で {@code STAMP_DELTA_ZERO}</li>
 *   <li>監査ログ呼び出し検証（暗号化対象を含まない）</li>
 *   <li>履歴記録検証（{@code stamp_event} 挿入）</li>
 *   <li>認可違反は伝播（{@code AccessControlService} からの例外）</li>
 *   <li>listOrgStamps / listCardStamps の認可 + 結果整形</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardStampService 単体テスト")
class PointCardStampServiceTest {

    private static final Long ORG_ID = 10L;
    private static final Long OTHER_ORG_ID = 99L;
    private static final Long STAFF_USER_ID = 100L;
    private static final Long CUSTOMER_USER_ID = 200L;

    @Mock
    private UserPointCardRepository cardRepository;

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private PointCardStampEventRepository stampEventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private PointCardStampService stampService;

    // ─────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────

    private PointCardProviderEntity sampleStampProvider(Long orgId) {
        PointCardProviderEntity provider = PointCardProviderEntity.builder()
                .code("cafe_stamp_" + orgId)
                .displayName("カフェ A スタンプ")
                .category(PointCardCategory.FOOD)
                .type(PointCardProviderType.SELF_ISSUED_STAMP)
                .organizationId(orgId)
                .brandColor("#993300")
                .defaultBarcodeFormat(BarcodeFormat.CODE128)
                .active(Boolean.TRUE)
                .build();
        provider.setId(UUID.randomUUID());
        return provider;
    }

    private UserPointCardEntity sampleCard(UUID providerId, Integer initialStampCount) {
        UserPointCardEntity card = UserPointCardEntity.builder()
                .userId(CUSTOMER_USER_ID)
                .providerId(providerId)
                .displayName("カフェ A スタンプ")
                .barcodeValue("CARDNUMBER_0001")
                .barcodeFormat(BarcodeFormat.CODE128)
                .last4("0001")
                .favorite(false)
                .displayOrder(0)
                .stampCount(initialStampCount)
                .build();
        card.setId(UUID.randomUUID());
        return card;
    }

    private UserEntity sampleUser(Long id, String displayName) {
        UserEntity user = UserEntity.builder().displayName(displayName).build();
        // BaseEntity.id は @GeneratedValue かつ setter 非公開のためリフレクションで設定
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // ─────────────────────────────────────────────
    // stamp 正常系
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("stamp: 正常系 delta=+1 で stamp_count がインクリメントされ履歴と監査ログが記録される")
    void stamp_plusOne_incrementsAndLogs() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), 3);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(STAFF_USER_ID, ORG_ID, "ORGANIZATION", "POINT_CARD_STAMP_ISSUE");
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any(UserPointCardEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(stampEventRepository.save(any(PointCardStampEventEntity.class)))
                .willAnswer(inv -> {
                    PointCardStampEventEntity e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });
        given(userRepository.findById(STAFF_USER_ID))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員 田中")));

        StampRequest req = new StampRequest(1, "10 杯目！");
        StampEventResponse response = stampService.stamp(
                ORG_ID, card.getId(), STAFF_USER_ID, req, "127.0.0.1", "UA", "sess-hash");

        assertThat(card.getStampCount()).isEqualTo(4);
        assertThat(response.delta()).isEqualTo(1);
        assertThat(response.cardId()).isEqualTo(card.getId());
        assertThat(response.providerId()).isEqualTo(provider.getId());
        assertThat(response.organizationId()).isEqualTo(ORG_ID);
        assertThat(response.pressedByUserId()).isEqualTo(STAFF_USER_ID);
        assertThat(response.pressedByUserDisplayName()).isEqualTo("店員 田中");
        assertThat(response.providerDisplayName()).isEqualTo("カフェ A スタンプ");
        assertThat(response.memo()).isEqualTo("10 杯目！");

        // 履歴記録の検証
        ArgumentCaptor<PointCardStampEventEntity> eventCaptor =
                ArgumentCaptor.forClass(PointCardStampEventEntity.class);
        verify(stampEventRepository).save(eventCaptor.capture());
        PointCardStampEventEntity savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.getCardId()).isEqualTo(card.getId());
        assertThat(savedEvent.getProviderId()).isEqualTo(provider.getId());
        assertThat(savedEvent.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(savedEvent.getDelta()).isEqualTo(1);
        assertThat(savedEvent.getPressedByUserId()).isEqualTo(STAFF_USER_ID);

        // 監査ログの検証（暗号化対象を含まない）
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_STAMP_ISSUED.name()),
                eq(STAFF_USER_ID),
                eq(null), eq(null), eq(ORG_ID),
                eq("127.0.0.1"), eq("UA"), eq("sess-hash"),
                metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertThat(metadata).contains("\"card_id\":\"" + card.getId() + "\"");
        assertThat(metadata).contains("\"delta\":1");
        assertThat(metadata).contains("\"new_stamp_count\":4");
        // 暗号化対象は絶対に含まれない
        assertThat(metadata).doesNotContain("CARDNUMBER_0001");        // barcode_value 漏洩なし
        assertThat(metadata).doesNotContain("カフェ A スタンプ");           // card displayName 漏洩なし
    }

    @Test
    @DisplayName("stamp: delta=+5 で stamp_count に 5 加算される")
    void stamp_plusFive_addsFive() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), 2);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stampEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        StampRequest req = new StampRequest(5, null);
        stampService.stamp(ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null);

        assertThat(card.getStampCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("stamp: delta=-1 で減算される（誤押印取消）")
    void stamp_minusOne_decrements() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), 5);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stampEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        StampRequest req = new StampRequest(-1, "誤押印取消");
        stampService.stamp(ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null);

        assertThat(card.getStampCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("stamp: stamp_count=0 で delta=-1 → 下限 0 ガードで 0 で止まる")
    void stamp_underflow_clampsToZero() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stampEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        StampRequest req = new StampRequest(-1, null);
        stampService.stamp(ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null);

        assertThat(card.getStampCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("stamp: stamp_count=null（Phase 1 既存カード）で delta=+1 → 1 になる")
    void stamp_nullStampCount_initializesToDelta() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), null);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stampEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        StampRequest req = new StampRequest(1, null);
        stampService.stamp(ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null);

        assertThat(card.getStampCount()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    // stamp 異常系
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("stamp: 認可なし（ADMIN/DEPUTY_ADMIN でない）→ AccessControlService の例外が伝播")
    void stamp_unauthorized_propagatesAccessException() {
        UUID cardId = UUID.randomUUID();
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrHasPermission(
                        STAFF_USER_ID, ORG_ID, "ORGANIZATION", "POINT_CARD_STAMP_ISSUE");

        StampRequest req = new StampRequest(1, null);
        assertThatThrownBy(() -> stampService.stamp(
                ORG_ID, cardId, STAFF_USER_ID, req, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(cardRepository, never()).findById(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("stamp: provider_id=null の自由入力カード → STAMP_INVALID_PROVIDER (012)")
    void stamp_freeInputCard_throws012() {
        UserPointCardEntity card = sampleCard(null, 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));

        StampRequest req = new StampRequest(1, null);
        assertThatThrownBy(() -> stampService.stamp(
                ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.STAMP_INVALID_PROVIDER);

        verify(cardRepository, never()).save(any());
        verify(stampEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("stamp: 他組織のカード → PROVIDER_NOT_FOUND (IDOR 防止で 404 隠蔽)")
    void stamp_otherOrgCard_throws007() {
        PointCardProviderEntity otherOrgProvider = sampleStampProvider(OTHER_ORG_ID);
        UserPointCardEntity card = sampleCard(otherOrgProvider.getId(), 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(otherOrgProvider.getId()))
                .willReturn(Optional.of(otherOrgProvider));

        StampRequest req = new StampRequest(1, null);
        assertThatThrownBy(() -> stampService.stamp(
                ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_FOUND);
    }

    @Test
    @DisplayName("stamp: EXTERNAL プロバイダー → STAMP_INVALID_PROVIDER_TYPE (013)")
    void stamp_externalProvider_throws013() {
        PointCardProviderEntity provider = PointCardProviderEntity.builder()
                .code("ext_x")
                .displayName("外部カード")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .organizationId(ORG_ID) // 同じ組織に擬似的に紐付けても type で弾く
                .active(Boolean.TRUE)
                .build();
        provider.setId(UUID.randomUUID());
        UserPointCardEntity card = sampleCard(provider.getId(), 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        StampRequest req = new StampRequest(1, null);
        assertThatThrownBy(() -> stampService.stamp(
                ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.STAMP_INVALID_PROVIDER_TYPE);
    }

    @Test
    @DisplayName("stamp: 停止済プロバイダー（active=false） → PROVIDER_NOT_FOUND (007)")
    void stamp_inactiveProvider_throws007() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        provider.setActive(Boolean.FALSE);
        UserPointCardEntity card = sampleCard(provider.getId(), 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        StampRequest req = new StampRequest(1, null);
        assertThatThrownBy(() -> stampService.stamp(
                ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_FOUND);
    }

    @Test
    @DisplayName("stamp: delta=0 → STAMP_DELTA_ZERO (014)")
    void stamp_deltaZero_throws014() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        StampRequest req = new StampRequest(0, null);
        assertThatThrownBy(() -> stampService.stamp(
                ORG_ID, card.getId(), STAFF_USER_ID, req, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.STAMP_DELTA_ZERO);

        verify(cardRepository, never()).save(any());
        verify(stampEventRepository, never()).save(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("stamp: カードが存在しない → CARD_NOT_FOUND (006)")
    void stamp_cardNotFound_throws006() {
        UUID cardId = UUID.randomUUID();
        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(anyLong(), anyLong(), anyString(), anyString());
        given(cardRepository.findById(cardId)).willReturn(Optional.empty());

        StampRequest req = new StampRequest(1, null);
        assertThatThrownBy(() -> stampService.stamp(
                ORG_ID, cardId, STAFF_USER_ID, req, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // listOrgStamps
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("listOrgStamps: 認可後、新着順ページングを返す（providerId 絞り込みなし）")
    void listOrgStamps_returnsPageOrdered() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), 1);

        PointCardStampEventEntity event = PointCardStampEventEntity.builder()
                .cardId(card.getId())
                .providerId(provider.getId())
                .organizationId(ORG_ID)
                .delta(1)
                .pressedByUserId(STAFF_USER_ID)
                .build();
        event.setId(UUID.randomUUID());

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");
        Pageable pageable = PageRequest.of(0, 20);
        Page<PointCardStampEventEntity> page = new PageImpl<>(List.of(event), pageable, 1);
        given(stampEventRepository.findByOrganizationIdOrderByPressedAtDesc(ORG_ID, pageable))
                .willReturn(page);
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(userRepository.findById(STAFF_USER_ID))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        Page<StampEventResponse> result =
                stampService.listOrgStamps(ORG_ID, STAFF_USER_ID, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        StampEventResponse first = result.getContent().get(0);
        assertThat(first.cardId()).isEqualTo(card.getId());
        assertThat(first.providerDisplayName()).isEqualTo("カフェ A スタンプ");
        assertThat(first.pressedByUserDisplayName()).isEqualTo("店員");
    }

    @Test
    @DisplayName("listOrgStamps: providerId 指定で絞り込みメソッドが呼ばれる")
    void listOrgStamps_withProviderFilter_callsFilteredQuery() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        Pageable pageable = PageRequest.of(0, 20);
        given(stampEventRepository.findByOrganizationIdAndProviderIdOrderByPressedAtDesc(
                ORG_ID, provider.getId(), pageable))
                .willReturn(Page.empty(pageable));

        Page<StampEventResponse> result =
                stampService.listOrgStamps(ORG_ID, STAFF_USER_ID, provider.getId(), pageable);

        assertThat(result.isEmpty()).isTrue();
        verify(stampEventRepository).findByOrganizationIdAndProviderIdOrderByPressedAtDesc(
                ORG_ID, provider.getId(), pageable);
        verify(stampEventRepository, never())
                .findByOrganizationIdOrderByPressedAtDesc(any(), any());
    }

    @Test
    @DisplayName("listOrgStamps: 認可なしは例外が伝播し、リポジトリは呼ばれない")
    void listOrgStamps_unauthorized_propagates() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");

        Pageable pageable = PageRequest.of(0, 20);
        assertThatThrownBy(() -> stampService.listOrgStamps(ORG_ID, STAFF_USER_ID, null, pageable))
                .isInstanceOf(BusinessException.class);

        verify(stampEventRepository, never())
                .findByOrganizationIdOrderByPressedAtDesc(any(), any());
    }

    // ─────────────────────────────────────────────
    // listCardStamps
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("listCardStamps: 認可後、カード履歴を返し押印者表示名を一括解決する")
    void listCardStamps_returnsListWithBulkDisplayNames() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), 2);
        Long staffA = 101L;
        Long staffB = 102L;

        PointCardStampEventEntity e1 = PointCardStampEventEntity.builder()
                .cardId(card.getId()).providerId(provider.getId())
                .organizationId(ORG_ID).delta(1).pressedByUserId(staffA).build();
        e1.setId(UUID.randomUUID());
        PointCardStampEventEntity e2 = PointCardStampEventEntity.builder()
                .cardId(card.getId()).providerId(provider.getId())
                .organizationId(ORG_ID).delta(1).pressedByUserId(staffB).build();
        e2.setId(UUID.randomUUID());

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(stampEventRepository.findByCardIdOrderByPressedAtDesc(card.getId()))
                .willReturn(List.of(e1, e2));
        given(userRepository.findAllById(any()))
                .willReturn(List.of(
                        sampleUser(staffA, "店員A"),
                        sampleUser(staffB, "店員B")
                ));

        List<StampEventResponse> result =
                stampService.listCardStamps(ORG_ID, card.getId(), STAFF_USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(StampEventResponse::pressedByUserDisplayName))
                .containsExactlyInAnyOrder("店員A", "店員B");
    }

    @Test
    @DisplayName("listCardStamps: 他組織のカード → CARD_NOT_FOUND (IDOR 防止)")
    void listCardStamps_otherOrgCard_throwsNotFound() {
        PointCardProviderEntity otherOrgProvider = sampleStampProvider(OTHER_ORG_ID);
        UserPointCardEntity card = sampleCard(otherOrgProvider.getId(), 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(otherOrgProvider.getId()))
                .willReturn(Optional.of(otherOrgProvider));

        assertThatThrownBy(() -> stampService.listCardStamps(ORG_ID, card.getId(), STAFF_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);

        verify(stampEventRepository, never()).findByCardIdOrderByPressedAtDesc(any());
    }

    @Test
    @DisplayName("listCardStamps: 自由入力カード（provider_id=null） → CARD_NOT_FOUND (IDOR 隠蔽)")
    void listCardStamps_nullProvider_throwsNotFound() {
        UserPointCardEntity card = sampleCard(null, 0);

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));

        assertThatThrownBy(() -> stampService.listCardStamps(ORG_ID, card.getId(), STAFF_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }
}
