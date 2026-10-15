package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.CreateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.UpdateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.UserPointCardDetailResponse;
import com.mannschaft.app.pointcard.dto.UserPointCardListItemResponse;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * {@link PointCardService} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.4 / §7
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>createCard: 正常系 (fuzzy match ヒット / 未ヒット) / 上限 200 / 規約未同意 / last4 算出</li>
 *   <li>getCard: 正常系 / 他人 ID で 404</li>
 *   <li>listMyCards: お気に入り順で復号値が返る / プロバイダー紐付け解決</li>
 *   <li>updateCard: 部分更新 / displayName 変更で provider 再マッチ</li>
 *   <li>deleteCard: 正常系 / 他人 ID で 404 / 監査ログ POINT_CARD_DELETED 記録</li>
 *   <li>recordUsed: 正常系 / 他人 ID で 404</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardService 単体テスト")
class PointCardServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;

    @Mock
    private UserPointCardRepository cardRepository;

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private ProviderMatchService providerMatchService;

    @Mock
    private PointCardUserSettingsService userSettingsService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PointCardService pointCardService;

    // ─────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────

    private PointCardProviderEntity sampleProvider() {
        return PointCardProviderEntity.builder()
                .code("tokyu_point")
                .displayName("東急ポイント")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#E60012")
                .logoUrl("logos/tokyu.png")
                .defaultBarcodeFormat(BarcodeFormat.CODE128)
                .active(Boolean.TRUE)
                .build();
    }

    private UserPointCardEntity sampleCard(Long owner, UUID providerId) {
        UserPointCardEntity card = UserPointCardEntity.builder()
                .userId(owner)
                .providerId(providerId)
                .displayName("東急ポイント")
                .barcodeValue("1234567890123")
                .barcodeFormat(BarcodeFormat.CODE128)
                .last4("0123")
                .favorite(false)
                .displayOrder(0)
                .build();
        card.setId(UUID.randomUUID());
        return card;
    }

    // ─────────────────────────────────────────────
    // createCard
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("createCard: fuzzy match ヒット → provider_id 設定 + 監査ログ provider_matched=true")
    void createCard_withFuzzyMatch_setsProviderIdAndLogs() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(cardRepository.countByUserId(USER_ID)).willReturn(5L);

        PointCardProviderEntity provider = sampleProvider();
        UUID providerId = UUID.randomUUID();
        provider.setId(providerId);
        given(providerMatchService.matchProvider("東急ポイント")).willReturn(Optional.of(provider));
        given(cardRepository.save(any(UserPointCardEntity.class)))
                .willAnswer(inv -> {
                    UserPointCardEntity arg = inv.getArgument(0);
                    arg.setId(UUID.randomUUID());
                    return arg;
                });

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "東急ポイント", "1234567890123", BarcodeFormat.CODE128,
                null, null, Boolean.TRUE);

        UserPointCardDetailResponse response = pointCardService.createCard(USER_ID, req);

        assertThat(response.providerMatched()).isTrue();
        assertThat(response.providerId()).isEqualTo(providerId);
        assertThat(response.providerCode()).isEqualTo("tokyu_point");
        assertThat(response.displayName()).isEqualTo("東急ポイント");
        assertThat(response.last4()).isEqualTo("0123");
        assertThat(response.favorite()).isTrue();

        // 監査ログに provider_matched=true / card_id が入る、暗号化対象は含まれない
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_CREATED.name()),
                eq(USER_ID),
                any(), any(), any(),
                any(), any(), any(),
                metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertThat(metadata).contains("\"provider_matched\":true");
        assertThat(metadata).contains("\"provider_code\":\"tokyu_point\"");
        assertThat(metadata).doesNotContain("1234567890123"); // barcode_value 漏洩なし
        assertThat(metadata).doesNotContain("東急ポイント");      // display_name 漏洩なし
    }

    @Test
    @DisplayName("createCard: fuzzy match 未ヒット → provider_id=null + 監査ログ provider_matched=false")
    void createCard_withoutFuzzyMatch_savesNullProvider() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(cardRepository.countByUserId(USER_ID)).willReturn(0L);
        given(providerMatchService.matchProvider("マイナーカード")).willReturn(Optional.empty());
        given(cardRepository.save(any(UserPointCardEntity.class)))
                .willAnswer(inv -> {
                    UserPointCardEntity arg = inv.getArgument(0);
                    arg.setId(UUID.randomUUID());
                    return arg;
                });

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "マイナーカード", "9876543210", BarcodeFormat.EAN13,
                "メモ", "テストメモ", null);

        UserPointCardDetailResponse response = pointCardService.createCard(USER_ID, req);

        assertThat(response.providerMatched()).isFalse();
        assertThat(response.providerId()).isNull();
        assertThat(response.providerCode()).isNull();
        assertThat(response.last4()).isEqualTo("3210");
        assertThat(response.nickname()).isEqualTo("メモ");
        assertThat(response.memo()).isEqualTo("テストメモ");
        assertThat(response.favorite()).isFalse(); // null → false default
    }

    @Test
    @DisplayName("createCard: 200 枚上限を超えると CARD_LIMIT_EXCEEDED")
    void createCard_overLimit_throwsCardLimitExceeded() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(cardRepository.countByUserId(USER_ID)).willReturn(200L);

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "カード", "1234", BarcodeFormat.CODE128, null, null, null);

        assertThatThrownBy(() -> pointCardService.createCard(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_LIMIT_EXCEEDED);

        verify(cardRepository, never()).save(any());
    }

    @Test
    @DisplayName("createCard: 規約未同意は WALLET_NOT_ENABLED を伝播する")
    void createCard_termsNotAccepted_propagatesWalletNotEnabled() {
        willThrow(new BusinessException(PointCardErrorCode.WALLET_NOT_ENABLED))
                .given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "カード", "1234", BarcodeFormat.CODE128, null, null, null);

        assertThatThrownBy(() -> pointCardService.createCard(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.WALLET_NOT_ENABLED);

        verify(cardRepository, never()).save(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("createCard: barcodeValue が 4 文字未満なら last4=null")
    void createCard_shortBarcode_last4IsNull() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(cardRepository.countByUserId(USER_ID)).willReturn(0L);
        given(providerMatchService.matchProvider(anyString())).willReturn(Optional.empty());
        given(cardRepository.save(any(UserPointCardEntity.class)))
                .willAnswer(inv -> {
                    UserPointCardEntity arg = inv.getArgument(0);
                    arg.setId(UUID.randomUUID());
                    return arg;
                });

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "短カード", "ABC", BarcodeFormat.CODE128, null, null, null);

        UserPointCardDetailResponse response = pointCardService.createCard(USER_ID, req);
        assertThat(response.last4()).isNull();
        assertThat(response.barcodeValue()).isEqualTo("ABC");
    }

    // ─────────────────────────────────────────────
    // getCard
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getCard: 本人なら詳細を返す（復号値含む）")
    void getCard_owner_returnsDetail() {
        UUID providerId = UUID.randomUUID();
        PointCardProviderEntity provider = sampleProvider();
        provider.setId(providerId);
        UserPointCardEntity card = sampleCard(USER_ID, providerId);

        given(cardRepository.findByIdAndUserId(card.getId(), USER_ID))
                .willReturn(Optional.of(card));
        given(providerRepository.findById(providerId)).willReturn(Optional.of(provider));

        UserPointCardDetailResponse response = pointCardService.getCard(card.getId(), USER_ID);

        assertThat(response.id()).isEqualTo(card.getId());
        assertThat(response.barcodeValue()).isEqualTo("1234567890123");
        assertThat(response.providerCode()).isEqualTo("tokyu_point");
    }

    @Test
    @DisplayName("getCard: 他人のカードは CARD_NOT_FOUND (IDOR 防止 — 403 ではなく 404)")
    void getCard_otherUser_throwsNotFound() {
        UUID cardId = UUID.randomUUID();
        given(cardRepository.findByIdAndUserId(cardId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> pointCardService.getCard(cardId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // listMyCards
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("listMyCards: 復号後 displayName が返る / プロバイダー解決される")
    void listMyCards_returnsDecryptedDisplayNameAndProvider() {
        UUID providerId = UUID.randomUUID();
        PointCardProviderEntity provider = sampleProvider();
        provider.setId(providerId);

        UserPointCardEntity card1 = sampleCard(USER_ID, providerId);
        card1.setFavorite(true);
        UserPointCardEntity card2 = sampleCard(USER_ID, null); // provider 未紐付け

        given(cardRepository.findByUserIdOrderByFavoriteDescDisplayOrderAscCreatedAtDesc(USER_ID))
                .willReturn(List.of(card1, card2));
        given(providerRepository.findById(providerId)).willReturn(Optional.of(provider));

        List<UserPointCardListItemResponse> result = pointCardService.listMyCards(USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).displayName()).isEqualTo("東急ポイント"); // 復号値
        assertThat(result.get(0).providerCode()).isEqualTo("tokyu_point");
        assertThat(result.get(0).favorite()).isTrue();
        assertThat(result.get(1).providerCode()).isNull(); // 紐付けなし
    }

    @Test
    @DisplayName("listMyCards: 0 件のとき空リストを返し N+1 クエリも発火しない")
    void listMyCards_empty_returnsEmptyList() {
        given(cardRepository.findByUserIdOrderByFavoriteDescDisplayOrderAscCreatedAtDesc(USER_ID))
                .willReturn(List.of());

        List<UserPointCardListItemResponse> result = pointCardService.listMyCards(USER_ID);

        assertThat(result).isEmpty();
        verify(providerRepository, never()).findById(any());
    }

    // ─────────────────────────────────────────────
    // updateCard
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("updateCard: null 以外のフィールドのみ部分更新")
    void updateCard_partialUpdate() {
        UserPointCardEntity card = sampleCard(USER_ID, null);
        given(cardRepository.findByIdAndUserId(card.getId(), USER_ID))
                .willReturn(Optional.of(card));
        given(cardRepository.save(any(UserPointCardEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateUserPointCardRequest req = new UpdateUserPointCardRequest(
                null, "新ニックネーム", null, Boolean.TRUE, 5);

        UserPointCardDetailResponse response = pointCardService.updateCard(
                card.getId(), USER_ID, req);

        assertThat(response.nickname()).isEqualTo("新ニックネーム");
        assertThat(response.favorite()).isTrue();
        assertThat(response.displayOrder()).isEqualTo(5);
        // displayName と memo は元のまま
        assertThat(response.displayName()).isEqualTo("東急ポイント");
        assertThat(response.memo()).isNull();
        // displayName 変更してないので provider 再マッチは走らない
        verify(providerMatchService, never()).matchProvider(any());
    }

    @Test
    @DisplayName("updateCard: displayName 変更で provider 再 fuzzy match される")
    void updateCard_displayNameChanged_rematchesProvider() {
        UserPointCardEntity card = sampleCard(USER_ID, null);
        given(cardRepository.findByIdAndUserId(card.getId(), USER_ID))
                .willReturn(Optional.of(card));

        PointCardProviderEntity provider = sampleProvider();
        UUID providerId = UUID.randomUUID();
        provider.setId(providerId);
        given(providerMatchService.matchProvider("dポイント")).willReturn(Optional.of(provider));
        given(cardRepository.save(any(UserPointCardEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateUserPointCardRequest req = new UpdateUserPointCardRequest(
                "dポイント", null, null, null, null);

        UserPointCardDetailResponse response = pointCardService.updateCard(
                card.getId(), USER_ID, req);

        assertThat(response.displayName()).isEqualTo("dポイント");
        assertThat(response.providerId()).isEqualTo(providerId);
        assertThat(response.providerMatched()).isTrue();
    }

    @Test
    @DisplayName("updateCard: 他人のカード ID は CARD_NOT_FOUND")
    void updateCard_otherUser_throwsNotFound() {
        UUID cardId = UUID.randomUUID();
        given(cardRepository.findByIdAndUserId(cardId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        UpdateUserPointCardRequest req = new UpdateUserPointCardRequest(
                "x", null, null, null, null);

        assertThatThrownBy(() -> pointCardService.updateCard(cardId, OTHER_USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // deleteCard
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("deleteCard: 本人のカードを物理削除 + 監査ログ POINT_CARD_DELETED 記録")
    void deleteCard_owner_deletesAndLogs() {
        UUID providerId = UUID.randomUUID();
        PointCardProviderEntity provider = sampleProvider();
        provider.setId(providerId);
        UserPointCardEntity card = sampleCard(USER_ID, providerId);
        given(cardRepository.findByIdAndUserId(card.getId(), USER_ID))
                .willReturn(Optional.of(card));
        given(providerRepository.findById(providerId)).willReturn(Optional.of(provider));

        pointCardService.deleteCard(card.getId(), USER_ID);

        verify(cardRepository).delete(card);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_DELETED.name()),
                eq(USER_ID),
                any(), any(), any(),
                any(), any(), any(),
                metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).contains("\"provider_code\":\"tokyu_point\"");
        assertThat(metadataCaptor.getValue()).contains(card.getId().toString());
    }

    @Test
    @DisplayName("deleteCard: 他人のカード ID は CARD_NOT_FOUND + 監査ログ記録されない")
    void deleteCard_otherUser_throwsNotFoundNoLog() {
        UUID cardId = UUID.randomUUID();
        given(cardRepository.findByIdAndUserId(cardId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> pointCardService.deleteCard(cardId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);

        verify(cardRepository, never()).delete(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // recordUsed
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("recordUsed: 本人なら last_used_at を更新し監査ログは記録しない")
    void recordUsed_owner_updatesLastUsedAt() {
        UserPointCardEntity card = sampleCard(USER_ID, null);
        given(cardRepository.findByIdAndUserId(card.getId(), USER_ID))
                .willReturn(Optional.of(card));
        given(cardRepository.save(any(UserPointCardEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        pointCardService.recordUsed(card.getId(), USER_ID);

        assertThat(card.getLastUsedAt()).isNotNull();
        verify(cardRepository).save(card);
        // 高頻度操作なので監査ログは出ない
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordUsed: 他人のカード ID は CARD_NOT_FOUND")
    void recordUsed_otherUser_throwsNotFound() {
        UUID cardId = UUID.randomUUID();
        given(cardRepository.findByIdAndUserId(cardId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> pointCardService.recordUsed(cardId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // 補助関数
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("computeLast4: 各種境界値")
    void computeLast4_boundaries() {
        assertThat(PointCardService.computeLast4(null)).isNull();
        assertThat(PointCardService.computeLast4("")).isNull();
        assertThat(PointCardService.computeLast4("123")).isNull();
        assertThat(PointCardService.computeLast4("1234")).isEqualTo("1234");
        assertThat(PointCardService.computeLast4("1234567890123")).isEqualTo("0123");
    }
}
