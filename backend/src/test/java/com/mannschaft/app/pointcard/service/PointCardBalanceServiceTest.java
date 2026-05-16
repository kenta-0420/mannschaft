package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.pointcard.dto.BalanceEventRequest;
import com.mannschaft.app.pointcard.dto.BalanceEventResponse;
import com.mannschaft.app.pointcard.entity.PointCardBalanceEventEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BalanceOperationType;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardBalanceEventRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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
 * {@link PointCardBalanceService} の単体テスト（F18 Phase 3 第二陣 2B）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.1
 *
 * <p>カバー観点（18+ 件）:
 * <ul>
 *   <li>charge 正常系（+1,000 / +1）</li>
 *   <li>charge 上限超過 → 018</li>
 *   <li>charge amount=0 → 016</li>
 *   <li>spend 正常系</li>
 *   <li>spend で event の delta が負値で保存される</li>
 *   <li>spend 残高不足 → 017 (INSUFFICIENT_BALANCE)</li>
 *   <li>spend amount=0 → 016</li>
 *   <li>refund 正常系（一部返金 / 全額返金）</li>
 *   <li>refund 元 event が CHARGE → 020</li>
 *   <li>refund 累計超過 → 020</li>
 *   <li>refund 元 event 不存在 → 006</li>
 *   <li>refund refundOfEventId=null → 016</li>
 *   <li>非 BALANCE type → 015</li>
 *   <li>IDOR (他組織) → 011</li>
 *   <li>provider_id=null → 012 (STAMP_INVALID_PROVIDER 流用)</li>
 *   <li>認可なし → AccessControlService 例外伝播</li>
 *   <li>listOrgEvents 正常 / 認可なし</li>
 *   <li>listCardEvents IDOR 検証</li>
 *   <li>監査ログに暗号化対象を含まない</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardBalanceService 単体テスト")
class PointCardBalanceServiceTest {

    private static final Long ORG_ID = 10L;
    private static final Long OTHER_ORG_ID = 99L;
    private static final Long STAFF_USER_ID = 100L;
    private static final Long CUSTOMER_USER_ID = 200L;

    @Mock
    private UserPointCardRepository cardRepository;

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private PointCardBalanceEventRepository balanceEventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private PointCardBalanceService balanceService;

    // ─────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────

    private PointCardProviderEntity sampleBalanceProvider(Long orgId) {
        PointCardProviderEntity provider = PointCardProviderEntity.builder()
                .code("cafe_balance_" + orgId)
                .displayName("カフェ A 残高")
                .category(PointCardCategory.FOOD)
                .type(PointCardProviderType.SELF_ISSUED_BALANCE)
                .organizationId(orgId)
                .brandColor("#993300")
                .defaultBarcodeFormat(BarcodeFormat.CODE128)
                .active(Boolean.TRUE)
                .build();
        provider.setId(UUID.randomUUID());
        return provider;
    }

    private UserPointCardEntity sampleCard(UUID providerId, BigDecimal initialBalance) {
        UserPointCardEntity card = UserPointCardEntity.builder()
                .userId(CUSTOMER_USER_ID)
                .providerId(providerId)
                .displayName("カフェ A 残高")
                .barcodeValue("CARDNUMBER_0001")
                .barcodeFormat(BarcodeFormat.CODE128)
                .last4("0001")
                .favorite(false)
                .displayOrder(0)
                .balance(initialBalance)
                .build();
        card.setId(UUID.randomUUID());
        return card;
    }

    private UserEntity sampleUser(Long id, String displayName) {
        UserEntity user = UserEntity.builder().displayName(displayName).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private PointCardBalanceEventEntity sampleSpentEvent(UUID cardId, UUID providerId,
                                                        BigDecimal absAmount) {
        PointCardBalanceEventEntity event = PointCardBalanceEventEntity.builder()
                .cardId(cardId)
                .providerId(providerId)
                .organizationId(ORG_ID)
                .operationType(BalanceOperationType.SPENT)
                .delta(absAmount.negate())
                .balanceAfter(new BigDecimal("0.00"))
                .operatedByUserId(STAFF_USER_ID)
                .build();
        event.setId(UUID.randomUUID());
        return event;
    }

    private BalanceEventRequest req(BalanceOperationType type, String amount) {
        return new BalanceEventRequest(type, new BigDecimal(amount), null, null);
    }

    private BalanceEventRequest reqWithNote(BalanceOperationType type, String amount, String note) {
        return new BalanceEventRequest(type, new BigDecimal(amount), note, null);
    }

    private BalanceEventRequest reqRefund(String amount, UUID refundOfEventId) {
        return new BalanceEventRequest(BalanceOperationType.REFUND,
                new BigDecimal(amount), null, refundOfEventId);
    }

    // ─────────────────────────────────────────────
    // CHARGE
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("charge: 正常系 +1000 で残高加算 / event 挿入 / 監査ログ記録")
    void charge_normal_addsAndLogs() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("500.00"));

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(balanceEventRepository.save(any())).willAnswer(inv -> {
            PointCardBalanceEventEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        given(userRepository.findById(STAFF_USER_ID))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員 太郎")));

        BalanceEventRequest request = reqWithNote(BalanceOperationType.CHARGE, "1000.00", "キャンペーン入金");
        BalanceEventResponse response = balanceService.charge(
                ORG_ID, card.getId(), STAFF_USER_ID, request, "127.0.0.1", "UA", "sess-hash");

        assertThat(card.getBalance()).isEqualByComparingTo("1500.00");
        assertThat(response.operationType()).isEqualTo(BalanceOperationType.CHARGE);
        assertThat(response.delta()).isEqualByComparingTo("1000.00");
        assertThat(response.balanceAfter()).isEqualByComparingTo("1500.00");
        assertThat(response.operatedByUserDisplayName()).isEqualTo("店員 太郎");
        assertThat(response.providerDisplayName()).isEqualTo("カフェ A 残高");

        // event 検証
        ArgumentCaptor<PointCardBalanceEventEntity> ec =
                ArgumentCaptor.forClass(PointCardBalanceEventEntity.class);
        verify(balanceEventRepository).save(ec.capture());
        PointCardBalanceEventEntity saved = ec.getValue();
        assertThat(saved.getOperationType()).isEqualTo(BalanceOperationType.CHARGE);
        assertThat(saved.getDelta()).isEqualByComparingTo("1000.00");
        assertThat(saved.getBalanceAfter()).isEqualByComparingTo("1500.00");
        assertThat(saved.getRefundOfEventId()).isNull();

        // 監査ログ検証（暗号化対象を含まない）
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_BALANCE_CHARGED.name()),
                eq(STAFF_USER_ID), eq(null), eq(null), eq(ORG_ID),
                eq("127.0.0.1"), eq("UA"), eq("sess-hash"),
                meta.capture());
        String metadata = meta.getValue();
        assertThat(metadata).contains("\"card_id\":\"" + card.getId() + "\"");
        assertThat(metadata).contains("\"delta\":\"1000.00\"");
        assertThat(metadata).contains("\"balance_after\":\"1500.00\"");
        assertThat(metadata).doesNotContain("CARDNUMBER_0001");
        assertThat(metadata).doesNotContain("カフェ A 残高");
    }

    @Test
    @DisplayName("charge: 残高 null の初期カードに +1 で残高 1 になる")
    void charge_nullBalance_initializes() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), null);

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(balanceEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        balanceService.charge(ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.CHARGE, "1.00"), null, null, null);

        assertThat(card.getBalance()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("charge: 残高上限 10,000,000 超過 → 018 BALANCE_LIMIT_EXCEEDED")
    void charge_overUpperLimit_throws018() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("9999999.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        assertThatThrownBy(() -> balanceService.charge(ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.CHARGE, "2.00"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.BALANCE_LIMIT_EXCEEDED);

        verify(cardRepository, never()).save(any());
        verify(balanceEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("charge: amount=0.00 → 016 BALANCE_DELTA_ZERO")
    void charge_zero_throws016() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("500.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        // amount=0.00 は Service 層でも弾く（Controller の DecimalMin は突破想定）
        BalanceEventRequest request = new BalanceEventRequest(
                BalanceOperationType.CHARGE, BigDecimal.ZERO, null, null);
        assertThatThrownBy(() -> balanceService.charge(ORG_ID, card.getId(), STAFF_USER_ID,
                request, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.BALANCE_DELTA_ZERO);
    }

    // ─────────────────────────────────────────────
    // SPENT
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("spend: 正常系 500 利用で残高減算、event の delta は負値で保存される")
    void spend_normal_subtractsAndStoresNegativeDelta() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("1500.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(balanceEventRepository.save(any())).willAnswer(inv -> {
            PointCardBalanceEventEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        BalanceEventResponse response = balanceService.spend(
                ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.SPENT, "500.00"), null, null, null);

        assertThat(card.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(response.delta()).isEqualByComparingTo("-500.00");
        assertThat(response.balanceAfter()).isEqualByComparingTo("1000.00");

        ArgumentCaptor<PointCardBalanceEventEntity> ec =
                ArgumentCaptor.forClass(PointCardBalanceEventEntity.class);
        verify(balanceEventRepository).save(ec.capture());
        assertThat(ec.getValue().getDelta()).isEqualByComparingTo("-500.00");
        assertThat(ec.getValue().getOperationType()).isEqualTo(BalanceOperationType.SPENT);

        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_BALANCE_SPENT.name()),
                anyLong(), eq(null), eq(null), eq(ORG_ID),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("spend: 残高不足 → 017 INSUFFICIENT_BALANCE（クランプしない）")
    void spend_insufficientBalance_throws017() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("100.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        assertThatThrownBy(() -> balanceService.spend(ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.SPENT, "101.00"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.INSUFFICIENT_BALANCE);

        // 残高は変わらない
        assertThat(card.getBalance()).isEqualByComparingTo("100.00");
        verify(cardRepository, never()).save(any());
        verify(balanceEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("spend: amount=0 → 016 BALANCE_DELTA_ZERO")
    void spend_zero_throws016() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("500.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        BalanceEventRequest request = new BalanceEventRequest(
                BalanceOperationType.SPENT, BigDecimal.ZERO, null, null);
        assertThatThrownBy(() -> balanceService.spend(ORG_ID, card.getId(), STAFF_USER_ID,
                request, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.BALANCE_DELTA_ZERO);
    }

    // ─────────────────────────────────────────────
    // REFUND
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("refund: 一部返金正常系 — 元 SPENT 1000 のうち 300 を返金")
    void refund_partial_normal() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("0.00"));
        PointCardBalanceEventEntity original = sampleSpentEvent(
                card.getId(), provider.getId(), new BigDecimal("1000.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findById(original.getId())).willReturn(Optional.of(original));
        given(balanceEventRepository.findByRefundOfEventId(original.getId())).willReturn(List.of());
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(balanceEventRepository.save(any())).willAnswer(inv -> {
            PointCardBalanceEventEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        BalanceEventResponse response = balanceService.refund(
                ORG_ID, card.getId(), STAFF_USER_ID,
                reqRefund("300.00", original.getId()), null, null, null);

        assertThat(card.getBalance()).isEqualByComparingTo("300.00");
        assertThat(response.operationType()).isEqualTo(BalanceOperationType.REFUND);
        assertThat(response.delta()).isEqualByComparingTo("300.00");
        assertThat(response.refundOfEventId()).isEqualTo(original.getId());

        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_BALANCE_REFUNDED.name()),
                anyLong(), eq(null), eq(null), eq(ORG_ID),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("refund: 全額返金 — 元 SPENT 1000 を一括で返金可能")
    void refund_fullAmount_ok() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("0.00"));
        PointCardBalanceEventEntity original = sampleSpentEvent(
                card.getId(), provider.getId(), new BigDecimal("1000.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findById(original.getId())).willReturn(Optional.of(original));
        given(balanceEventRepository.findByRefundOfEventId(original.getId())).willReturn(List.of());
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(balanceEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        balanceService.refund(ORG_ID, card.getId(), STAFF_USER_ID,
                reqRefund("1000.00", original.getId()), null, null, null);

        assertThat(card.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("refund: 元 event が CHARGE → 020 REFUND_EXCEEDS_ORIGINAL")
    void refund_originalIsCharge_throws020() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("0.00"));
        PointCardBalanceEventEntity original = PointCardBalanceEventEntity.builder()
                .cardId(card.getId())
                .providerId(provider.getId())
                .organizationId(ORG_ID)
                .operationType(BalanceOperationType.CHARGE)
                .delta(new BigDecimal("1000.00"))
                .balanceAfter(new BigDecimal("1000.00"))
                .operatedByUserId(STAFF_USER_ID)
                .build();
        original.setId(UUID.randomUUID());

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findById(original.getId())).willReturn(Optional.of(original));

        assertThatThrownBy(() -> balanceService.refund(ORG_ID, card.getId(), STAFF_USER_ID,
                reqRefund("100.00", original.getId()), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.REFUND_EXCEEDS_ORIGINAL);

        verify(cardRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund: 累計超過 — 既存返金 800 + 今回 300 > 元 1000 → 020")
    void refund_accumulatedExceeds_throws020() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("800.00"));
        PointCardBalanceEventEntity original = sampleSpentEvent(
                card.getId(), provider.getId(), new BigDecimal("1000.00"));
        PointCardBalanceEventEntity priorRefund = PointCardBalanceEventEntity.builder()
                .cardId(card.getId())
                .providerId(provider.getId())
                .organizationId(ORG_ID)
                .operationType(BalanceOperationType.REFUND)
                .delta(new BigDecimal("800.00"))
                .balanceAfter(new BigDecimal("800.00"))
                .refundOfEventId(original.getId())
                .operatedByUserId(STAFF_USER_ID)
                .build();
        priorRefund.setId(UUID.randomUUID());

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findById(original.getId())).willReturn(Optional.of(original));
        given(balanceEventRepository.findByRefundOfEventId(original.getId()))
                .willReturn(List.of(priorRefund));

        assertThatThrownBy(() -> balanceService.refund(ORG_ID, card.getId(), STAFF_USER_ID,
                reqRefund("300.00", original.getId()), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.REFUND_EXCEEDS_ORIGINAL);
    }

    @Test
    @DisplayName("refund: 累計が元の絶対値ぴったり — 既存 700 + 今回 300 = 1000 → 成功")
    void refund_accumulatedEquals_ok() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("700.00"));
        PointCardBalanceEventEntity original = sampleSpentEvent(
                card.getId(), provider.getId(), new BigDecimal("1000.00"));
        PointCardBalanceEventEntity priorRefund = PointCardBalanceEventEntity.builder()
                .cardId(card.getId())
                .providerId(provider.getId())
                .organizationId(ORG_ID)
                .operationType(BalanceOperationType.REFUND)
                .delta(new BigDecimal("700.00"))
                .balanceAfter(new BigDecimal("700.00"))
                .refundOfEventId(original.getId())
                .operatedByUserId(STAFF_USER_ID)
                .build();
        priorRefund.setId(UUID.randomUUID());

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findById(original.getId())).willReturn(Optional.of(original));
        given(balanceEventRepository.findByRefundOfEventId(original.getId()))
                .willReturn(List.of(priorRefund));
        given(cardRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(balanceEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findById(anyLong()))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        balanceService.refund(ORG_ID, card.getId(), STAFF_USER_ID,
                reqRefund("300.00", original.getId()), null, null, null);

        assertThat(card.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("refund: 元 event 不存在 → CARD_NOT_FOUND (006)")
    void refund_originalNotFound_throws006() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("0.00"));
        UUID missingId = UUID.randomUUID();

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findById(missingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> balanceService.refund(ORG_ID, card.getId(), STAFF_USER_ID,
                reqRefund("100.00", missingId), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    @Test
    @DisplayName("refund: refundOfEventId=null → BALANCE_DELTA_ZERO (016) で拒否")
    void refund_nullRefundOf_throws016() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("0.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        BalanceEventRequest request = new BalanceEventRequest(
                BalanceOperationType.REFUND, new BigDecimal("100.00"), null, null);
        assertThatThrownBy(() -> balanceService.refund(ORG_ID, card.getId(), STAFF_USER_ID,
                request, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.BALANCE_DELTA_ZERO);
    }

    @Test
    @DisplayName("refund: 元 event の cardId が違う → CARD_NOT_FOUND (IDOR 防止)")
    void refund_otherCardEvent_throwsNotFound() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("0.00"));
        // 元 event は別のカード ID
        PointCardBalanceEventEntity original = sampleSpentEvent(
                UUID.randomUUID(), provider.getId(), new BigDecimal("500.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findById(original.getId())).willReturn(Optional.of(original));

        assertThatThrownBy(() -> balanceService.refund(ORG_ID, card.getId(), STAFF_USER_ID,
                reqRefund("100.00", original.getId()), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // 共通検証（IDOR / provider 検証）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("非 BALANCE type（STAMP） → 015 BALANCE_INVALID_PROVIDER_TYPE")
    void nonBalanceType_throws015() {
        PointCardProviderEntity stampProvider = PointCardProviderEntity.builder()
                .code("stamp_p")
                .displayName("スタンプ")
                .category(PointCardCategory.FOOD)
                .type(PointCardProviderType.SELF_ISSUED_STAMP)
                .organizationId(ORG_ID)
                .active(Boolean.TRUE)
                .build();
        stampProvider.setId(UUID.randomUUID());
        UserPointCardEntity card = sampleCard(stampProvider.getId(), new BigDecimal("0.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(stampProvider.getId())).willReturn(Optional.of(stampProvider));

        assertThatThrownBy(() -> balanceService.charge(ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.CHARGE, "100.00"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.BALANCE_INVALID_PROVIDER_TYPE);
    }

    @Test
    @DisplayName("他組織のプロバイダー → 011 PROVIDER_NOT_OWNED (IDOR 防止)")
    void otherOrg_throws011() {
        PointCardProviderEntity otherOrgProvider = sampleBalanceProvider(OTHER_ORG_ID);
        UserPointCardEntity card = sampleCard(otherOrgProvider.getId(), new BigDecimal("0.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(otherOrgProvider.getId()))
                .willReturn(Optional.of(otherOrgProvider));

        assertThatThrownBy(() -> balanceService.charge(ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.CHARGE, "100.00"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_OWNED);
    }

    @Test
    @DisplayName("provider_id=null の自由入力カード → 012 STAMP_INVALID_PROVIDER")
    void nullProvider_throws012() {
        UserPointCardEntity card = sampleCard(null, new BigDecimal("0.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));

        assertThatThrownBy(() -> balanceService.charge(ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.CHARGE, "100.00"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.STAMP_INVALID_PROVIDER);
    }

    @Test
    @DisplayName("認可なし → AccessControlService の例外が伝播し、save は呼ばれない")
    void unauthorized_propagates() {
        UUID cardId = UUID.randomUUID();
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> balanceService.charge(ORG_ID, cardId, STAFF_USER_ID,
                req(BalanceOperationType.CHARGE, "100.00"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(cardRepository, never()).findById(any());
        verify(cardRepository, never()).save(any());
        verify(balanceEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("停止済プロバイダー → 007 PROVIDER_NOT_FOUND")
    void inactiveProvider_throws007() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        provider.setActive(Boolean.FALSE);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("0.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        assertThatThrownBy(() -> balanceService.charge(ORG_ID, card.getId(), STAFF_USER_ID,
                req(BalanceOperationType.CHARGE, "100.00"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // listOrgEvents / listCardEvents
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("listOrgEvents: 認可後、新着順ページングを返す")
    void listOrgEvents_returnsPage() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("100.00"));
        PointCardBalanceEventEntity event = PointCardBalanceEventEntity.builder()
                .cardId(card.getId())
                .providerId(provider.getId())
                .organizationId(ORG_ID)
                .operationType(BalanceOperationType.CHARGE)
                .delta(new BigDecimal("100.00"))
                .balanceAfter(new BigDecimal("100.00"))
                .operatedByUserId(STAFF_USER_ID)
                .build();
        event.setId(UUID.randomUUID());

        willDoNothing().given(accessControlService).checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");
        Pageable pageable = PageRequest.of(0, 20);
        Page<PointCardBalanceEventEntity> page = new PageImpl<>(List.of(event), pageable, 1);
        given(balanceEventRepository.findByOrganizationIdOrderByOperatedAtDesc(ORG_ID, pageable))
                .willReturn(page);
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(userRepository.findById(STAFF_USER_ID))
                .willReturn(Optional.of(sampleUser(STAFF_USER_ID, "店員")));

        Page<BalanceEventResponse> result =
                balanceService.listOrgEvents(ORG_ID, STAFF_USER_ID, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).cardId()).isEqualTo(card.getId());
        assertThat(result.getContent().get(0).providerDisplayName()).isEqualTo("カフェ A 残高");
    }

    @Test
    @DisplayName("listOrgEvents: providerId 絞り込みでフィルタクエリが呼ばれる")
    void listOrgEvents_withProviderFilter_callsFiltered() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        Pageable pageable = PageRequest.of(0, 20);
        given(balanceEventRepository.findByOrganizationIdAndProviderIdOrderByOperatedAtDesc(
                ORG_ID, provider.getId(), pageable))
                .willReturn(Page.empty(pageable));

        balanceService.listOrgEvents(ORG_ID, STAFF_USER_ID, provider.getId(), pageable);

        verify(balanceEventRepository).findByOrganizationIdAndProviderIdOrderByOperatedAtDesc(
                ORG_ID, provider.getId(), pageable);
        verify(balanceEventRepository, never())
                .findByOrganizationIdOrderByOperatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("listCardEvents: 他組織のカード → CARD_NOT_FOUND (IDOR 防止)")
    void listCardEvents_otherOrg_throwsNotFound() {
        PointCardProviderEntity otherOrg = sampleBalanceProvider(OTHER_ORG_ID);
        UserPointCardEntity card = sampleCard(otherOrg.getId(), new BigDecimal("0.00"));

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(otherOrg.getId())).willReturn(Optional.of(otherOrg));

        assertThatThrownBy(() -> balanceService.listCardEvents(ORG_ID, card.getId(), STAFF_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    @Test
    @DisplayName("listCardEvents: 認可後、カード履歴を新着順に返す")
    void listCardEvents_returnsList() {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleCard(provider.getId(), new BigDecimal("1000.00"));
        PointCardBalanceEventEntity e1 = PointCardBalanceEventEntity.builder()
                .cardId(card.getId()).providerId(provider.getId())
                .organizationId(ORG_ID).operationType(BalanceOperationType.CHARGE)
                .delta(new BigDecimal("1000.00")).balanceAfter(new BigDecimal("1000.00"))
                .operatedByUserId(STAFF_USER_ID).build();
        e1.setId(UUID.randomUUID());

        willDoNothing().given(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(balanceEventRepository.findByCardIdOrderByOperatedAtDesc(card.getId()))
                .willReturn(List.of(e1));
        given(userRepository.findAllById(any()))
                .willReturn(List.of(sampleUser(STAFF_USER_ID, "店員")));

        List<BalanceEventResponse> result =
                balanceService.listCardEvents(ORG_ID, card.getId(), STAFF_USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).operatedByUserDisplayName()).isEqualTo("店員");
    }
}
