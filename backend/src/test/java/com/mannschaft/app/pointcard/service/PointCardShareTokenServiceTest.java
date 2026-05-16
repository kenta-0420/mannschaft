package com.mannschaft.app.pointcard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.ResolveTokenResponse;
import com.mannschaft.app.pointcard.dto.ShareTokenResponse;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PointCardShareTokenService} の単体テスト（F18 Phase 3 第二陣 2A）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §16 / §9
 *
 * <p>カバー観点:
 * <ul>
 *   <li>generate 正常系（SET NX EX 300 + UUID 形式トークン + deepLinkUrl）</li>
 *   <li>generate IDOR — 他人のカードで {@code POINT_CARD_006 CARD_NOT_FOUND}</li>
 *   <li>resolve 正常系 — GETDEL で cardId 特定 + ResolveTokenResponse 形状</li>
 *   <li>resolve 不存在 / 期限切れ で {@code POINT_CARD_019 TOKEN_NOT_FOUND}</li>
 *   <li>resolve 消費後再取得（2 回目）も同じく {@code POINT_CARD_019}</li>
 *   <li>resolve IDOR — 他組織のカードで {@code POINT_CARD_011 PROVIDER_NOT_OWNED}</li>
 *   <li>resolve provider 紐付けなしで {@code POINT_CARD_011}</li>
 *   <li>resolve で GETDEL が呼ばれること（atomic 削除検証）</li>
 *   <li>resolve のレスポンスが暗号化対象（displayName / barcodeValue / nickname / memo）を含まない</li>
 *   <li>resolve 認可違反（非 ADMIN）は AccessControlService からの例外を伝播</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardShareTokenService 単体テスト")
class PointCardShareTokenServiceTest {

    private static final Long ORG_ID = 10L;
    private static final Long OTHER_ORG_ID = 99L;
    private static final Long STAFF_USER_ID = 100L;
    private static final Long CUSTOMER_USER_ID = 200L;
    private static final Long OTHER_USER_ID = 300L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserPointCardRepository cardRepository;

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private AccessControlService accessControlService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PointCardShareTokenService shareTokenService;

    @BeforeEach
    void setUp() {
        // ObjectMapper は new で生成（InjectMocks 後に上書き）
        shareTokenService = new PointCardShareTokenService(
                redisTemplate, objectMapper, cardRepository, providerRepository, accessControlService);
    }

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

    private PointCardProviderEntity sampleBalanceProvider(Long orgId) {
        PointCardProviderEntity provider = PointCardProviderEntity.builder()
                .code("cafe_balance_" + orgId)
                .displayName("カフェ A 残高")
                .category(PointCardCategory.FOOD)
                .type(PointCardProviderType.SELF_ISSUED_BALANCE)
                .organizationId(orgId)
                .brandColor("#003399")
                .defaultBarcodeFormat(BarcodeFormat.CODE128)
                .active(Boolean.TRUE)
                .build();
        provider.setId(UUID.randomUUID());
        return provider;
    }

    private UserPointCardEntity sampleStampCard(UUID providerId, Long userId, Integer stampCount) {
        UserPointCardEntity card = UserPointCardEntity.builder()
                .userId(userId)
                .providerId(providerId)
                .displayName("ヒミツのカード名")            // 暗号化対象 — 漏洩検証用
                .nickname("ヒミツのニックネーム")           // 暗号化対象
                .barcodeValue("CARDNUMBER_HIMITSU_9999")   // 暗号化対象
                .barcodeFormat(BarcodeFormat.CODE128)
                .last4("9999")
                .memo("ヒミツのメモ")                       // 暗号化対象
                .favorite(false)
                .displayOrder(0)
                .stampCount(stampCount)
                .build();
        card.setId(UUID.randomUUID());
        return card;
    }

    private UserPointCardEntity sampleBalanceCard(UUID providerId, Long userId, BigDecimal balance) {
        UserPointCardEntity card = UserPointCardEntity.builder()
                .userId(userId)
                .providerId(providerId)
                .displayName("ヒミツの残高カード")
                .barcodeValue("BALANCE_NUM_8888")
                .barcodeFormat(BarcodeFormat.CODE128)
                .last4("8888")
                .favorite(false)
                .displayOrder(0)
                .balance(balance)
                .build();
        card.setId(UUID.randomUUID());
        return card;
    }

    // ─────────────────────────────────────────────
    // generate
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("generate: 正常系 — UUID トークン発行 + Valkey SET NX EX 300 + deepLinkUrl")
    void generate_success() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleStampCard(provider.getId(), CUSTOMER_USER_ID, 3);

        given(cardRepository.findByIdAndUserId(card.getId(), CUSTOMER_USER_ID))
                .willReturn(Optional.of(card));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), eq(PointCardShareTokenService.TTL)))
                .willReturn(Boolean.TRUE);

        ShareTokenResponse response = shareTokenService.generate(CUSTOMER_USER_ID, card.getId());

        // トークンは UUID 形式
        assertThat(response.token()).isNotBlank();
        assertThat(response.token()).hasSize(36);
        // UUID.fromString が成功する（フォーマット検証）
        UUID.fromString(response.token());

        // expiresAt は現在より未来
        assertThat(response.expiresAt()).isAfter(java.time.OffsetDateTime.now().minusSeconds(1));
        assertThat(response.expiresAt()).isBefore(java.time.OffsetDateTime.now().plus(Duration.ofMinutes(6)));

        // deepLinkUrl の形状
        assertThat(response.deepLinkUrl())
                .startsWith("mannschaft://wallet/share?token=")
                .endsWith(response.token());

        // SET NX が正しいキーで呼ばれた
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(keyCaptor.capture(), jsonCaptor.capture(),
                eq(PointCardShareTokenService.TTL));
        assertThat(keyCaptor.getValue())
                .startsWith(PointCardShareTokenService.KEY_PREFIX)
                .endsWith(response.token());

        // JSON ペイロードに cardId / userId / expiresAt が含まれる
        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"cardId\":\"" + card.getId() + "\"");
        assertThat(json).contains("\"userId\":" + CUSTOMER_USER_ID);
        assertThat(json).contains("\"expiresAt\":");
    }

    @Test
    @DisplayName("generate: 他人のカードで CARD_NOT_FOUND（IDOR 防止）")
    void generate_otherUserCard_throwsCardNotFound() {
        UUID cardId = UUID.randomUUID();
        given(cardRepository.findByIdAndUserId(cardId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> shareTokenService.generate(OTHER_USER_ID, cardId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);

        // Valkey 書き込みは発生しない
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("generate: SET NX が false を返す（UUID 衝突）と IllegalStateException")
    void generate_setNxFails_throwsIllegalState() {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleStampCard(provider.getId(), CUSTOMER_USER_ID, 0);

        given(cardRepository.findByIdAndUserId(card.getId(), CUSTOMER_USER_ID))
                .willReturn(Optional.of(card));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Boolean.FALSE);

        assertThatThrownBy(() -> shareTokenService.generate(CUSTOMER_USER_ID, card.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collision");
    }

    // ─────────────────────────────────────────────
    // resolve
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("resolve: 正常系 STAMP — GETDEL で cardId 特定 + currentStampCount 返却")
    void resolve_success_stamp() throws Exception {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleStampCard(provider.getId(), CUSTOMER_USER_ID, 5);
        String token = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "cardId", card.getId().toString(),
                "userId", CUSTOMER_USER_ID,
                "expiresAt", "2026-05-14T11:00:00Z"));

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(PointCardShareTokenService.KEY_PREFIX + token))
                .willReturn(json);
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        ResolveTokenResponse response = shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token);

        assertThat(response.cardId()).isEqualTo(card.getId());
        assertThat(response.providerId()).isEqualTo(provider.getId());
        assertThat(response.providerDisplayName()).isEqualTo("カフェ A スタンプ");
        assertThat(response.providerType()).isEqualTo(PointCardProviderType.SELF_ISSUED_STAMP);
        assertThat(response.last4()).isEqualTo("9999");
        assertThat(response.currentStampCount()).isEqualTo(5);
        assertThat(response.currentBalance()).isNull();

        // GETDEL が呼ばれたこと（atomic 削除）
        verify(valueOperations, times(1))
                .getAndDelete(PointCardShareTokenService.KEY_PREFIX + token);
    }

    @Test
    @DisplayName("resolve: 正常系 BALANCE — currentBalance のみ返り currentStampCount は null")
    void resolve_success_balance() throws Exception {
        PointCardProviderEntity provider = sampleBalanceProvider(ORG_ID);
        UserPointCardEntity card = sampleBalanceCard(
                provider.getId(), CUSTOMER_USER_ID, new BigDecimal("1250.00"));
        String token = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "cardId", card.getId().toString(),
                "userId", CUSTOMER_USER_ID,
                "expiresAt", "2026-05-14T11:00:00Z"));

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn(json);
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        ResolveTokenResponse response = shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token);

        assertThat(response.providerType()).isEqualTo(PointCardProviderType.SELF_ISSUED_BALANCE);
        assertThat(response.currentBalance()).isEqualByComparingTo("1250.00");
        assertThat(response.currentStampCount()).isNull();
    }

    @Test
    @DisplayName("resolve: 不存在トークンで TOKEN_NOT_FOUND")
    void resolve_notFound_throws019() {
        String token = UUID.randomUUID().toString();
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn(null);

        assertThatThrownBy(() -> shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PointCardErrorCode.TOKEN_NOT_FOUND);

        // カード取得は走らない
        verify(cardRepository, never()).findById(any(UUID.class));
    }

    @Test
    @DisplayName("resolve: 消費後再取得で TOKEN_NOT_FOUND（再生防止）")
    void resolve_consumedTwice_secondCallThrows019() throws Exception {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleStampCard(provider.getId(), CUSTOMER_USER_ID, 1);
        String token = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "cardId", card.getId().toString(),
                "userId", CUSTOMER_USER_ID,
                "expiresAt", "2026-05-14T11:00:00Z"));

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        // 1 回目は値を返し、2 回目は null（GETDEL 後）
        given(valueOperations.getAndDelete(anyString()))
                .willReturn(json)
                .willReturn(null);
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        // 1 回目は成功
        ResolveTokenResponse first = shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token);
        assertThat(first.cardId()).isEqualTo(card.getId());

        // 2 回目は 019
        assertThatThrownBy(() -> shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PointCardErrorCode.TOKEN_NOT_FOUND);
    }

    @Test
    @DisplayName("resolve: 他組織のカードで PROVIDER_NOT_OWNED（IDOR 防止）")
    void resolve_otherOrgCard_throwsProviderNotOwned() throws Exception {
        PointCardProviderEntity otherOrgProvider = sampleStampProvider(OTHER_ORG_ID);
        UserPointCardEntity card = sampleStampCard(
                otherOrgProvider.getId(), CUSTOMER_USER_ID, 2);
        String token = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "cardId", card.getId().toString(),
                "userId", CUSTOMER_USER_ID,
                "expiresAt", "2026-05-14T11:00:00Z"));

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn(json);
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(otherOrgProvider.getId()))
                .willReturn(Optional.of(otherOrgProvider));

        // ORG_ID で resolve しようとするが、provider は OTHER_ORG_ID 所属 → 011
        assertThatThrownBy(() -> shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_OWNED);
    }

    @Test
    @DisplayName("resolve: provider_id 紐付けなしカードで PROVIDER_NOT_OWNED")
    void resolve_cardWithoutProvider_throwsProviderNotOwned() throws Exception {
        UserPointCardEntity card = UserPointCardEntity.builder()
                .userId(CUSTOMER_USER_ID)
                .providerId(null)
                .displayName("外部カード")
                .barcodeValue("EXTERNAL")
                .barcodeFormat(BarcodeFormat.CODE128)
                .last4("0000")
                .build();
        card.setId(UUID.randomUUID());

        String token = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "cardId", card.getId().toString(),
                "userId", CUSTOMER_USER_ID,
                "expiresAt", "2026-05-14T11:00:00Z"));

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn(json);
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));

        assertThatThrownBy(() -> shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_OWNED);

        // provider 検索は走らない
        verify(providerRepository, never()).findById(any(UUID.class));
    }

    @Test
    @DisplayName("resolve: レスポンスに暗号化対象 (displayName/nickname/barcodeValue/memo) が含まれない")
    void resolve_responseDoesNotContainEncryptedFields() throws Exception {
        PointCardProviderEntity provider = sampleStampProvider(ORG_ID);
        UserPointCardEntity card = sampleStampCard(provider.getId(), CUSTOMER_USER_ID, 7);
        // card は "ヒミツのカード名" / "ヒミツのニックネーム" / "CARDNUMBER_HIMITSU_9999" / "ヒミツのメモ" を保持

        String token = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "cardId", card.getId().toString(),
                "userId", CUSTOMER_USER_ID,
                "expiresAt", "2026-05-14T11:00:00Z"));

        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn(json);
        given(cardRepository.findById(card.getId())).willReturn(Optional.of(card));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        ResolveTokenResponse response = shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token);

        // ResolveTokenResponse は record で固定フィールドのみ。toString() に暗号化対象が漏れないこと
        String responseString = response.toString();
        assertThat(responseString).doesNotContain("ヒミツのカード名");
        assertThat(responseString).doesNotContain("ヒミツのニックネーム");
        assertThat(responseString).doesNotContain("CARDNUMBER_HIMITSU_9999");
        assertThat(responseString).doesNotContain("ヒミツのメモ");
        // last4 は店主側に最小情報として開示 OK
        assertThat(responseString).contains("9999");
    }

    @Test
    @DisplayName("resolve: 認可違反（非 ADMIN）は AccessControlService からの例外を伝播")
    void resolve_accessDenied_propagatesException() {
        String token = UUID.randomUUID().toString();
        RuntimeException accessException = new RuntimeException("ACCESS_DENIED");
        willThrow(accessException).given(accessControlService)
                .checkAdminOrAbove(STAFF_USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token))
                .isSameAs(accessException);

        // Valkey アクセスは発生しない
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("resolve: JSON ペイロード破損で TOKEN_NOT_FOUND（既に削除済なのでデータ復元できず安全側に倒す）")
    void resolve_invalidJson_throws019() {
        String token = UUID.randomUUID().toString();
        willDoNothing().given(accessControlService)
                .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn("not-a-json{");

        assertThatThrownBy(() -> shareTokenService.resolve(STAFF_USER_ID, ORG_ID, token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PointCardErrorCode.TOKEN_NOT_FOUND);
    }
}
