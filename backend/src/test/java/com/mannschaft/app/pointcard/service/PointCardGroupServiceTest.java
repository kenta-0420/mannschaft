package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.AuthWebAuthnService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.CreateGroupRequest;
import com.mannschaft.app.pointcard.dto.GroupDetailResponse;
import com.mannschaft.app.pointcard.dto.GroupListItemResponse;
import com.mannschaft.app.pointcard.dto.PointCardUserSettingsResponse;
import com.mannschaft.app.pointcard.dto.UpdateGroupRequest;
import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;
import com.mannschaft.app.pointcard.entity.PointCardGroupItemEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardGroupItemRepository;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
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
 * {@link PointCardGroupService} 単体テスト（S3）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.3 / §5.4 / §6 (Groups API) / §11
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>createGroup: 規約検証 / 50 個上限 / 20 枚上限 / IDOR / 監査ログ POINT_CARD_GROUP_CREATED</li>
 *   <li>updateGroup: 部分更新 / cardIds 差し替え / 20 枚上限</li>
 *   <li>deleteGroup: 本人確認 / 監査ログ POINT_CARD_GROUP_DELETED</li>
 *   <li>startPresentation: POINT_CARD_VIEWED 監査ログが 1 件のみ発火</li>
 *   <li>listMyGroups / getGroupDetail の正常系</li>
 *   <li>個別カード閲覧では POINT_CARD_VIEWED が発火しないこと</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardGroupService 単体テスト")
class PointCardGroupServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;

    @Mock
    private PointCardGroupRepository groupRepository;

    @Mock
    private PointCardGroupItemRepository itemRepository;

    @Mock
    private UserPointCardRepository cardRepository;

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private PointCardUserSettingsService userSettingsService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AuthWebAuthnService authWebAuthnService;

    @InjectMocks
    private PointCardGroupService groupService;

    // ─────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────

    private PointCardGroupEntity sampleGroup(Long owner) {
        PointCardGroupEntity g = PointCardGroupEntity.builder()
                .userId(owner)
                .name("東急ハンズ用")
                .emoji("🛍️")
                .displayOrder(0)
                .build();
        g.setId(UUID.randomUUID());
        return g;
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

    private PointCardProviderEntity sampleProvider() {
        PointCardProviderEntity p = PointCardProviderEntity.builder()
                .code("tokyu_point")
                .displayName("東急ポイント")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#E60012")
                .logoUrl("logos/tokyu.png")
                .defaultBarcodeFormat(BarcodeFormat.CODE128)
                .active(Boolean.TRUE)
                .build();
        p.setId(UUID.randomUUID());
        return p;
    }

    // ─────────────────────────────────────────────
    // createGroup
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("createGroup: 空グループ作成 / 監査ログ POINT_CARD_GROUP_CREATED が card_count=0 で発火")
    void createGroup_empty_recordsAuditWithZero() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(groupRepository.countByUserId(USER_ID)).willReturn(0L);
        given(groupRepository.save(any(PointCardGroupEntity.class)))
                .willAnswer(inv -> {
                    PointCardGroupEntity g = inv.getArgument(0);
                    g.setId(UUID.randomUUID());
                    return g;
                });
        // loadGroupItems の中間取得は空リスト
        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(any()))
                .willReturn(List.of());

        CreateGroupRequest req = new CreateGroupRequest("東急ハンズ用", "🛍️", null);
        GroupDetailResponse res = groupService.createGroup(USER_ID, req);

        assertThat(res.name()).isEqualTo("東急ハンズ用");
        assertThat(res.items()).isEmpty();

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_GROUP_CREATED.name()),
                eq(USER_ID),
                any(), any(), any(),
                any(), any(), any(),
                metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).contains("\"card_count\":0");
    }

    @Test
    @DisplayName("createGroup: cardIds 指定でアイテム保存 + 監査ログに card_count が入る")
    void createGroup_withCardIds_savesItemsAndLogsCount() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(groupRepository.countByUserId(USER_ID)).willReturn(5L);

        UserPointCardEntity card1 = sampleCard(USER_ID, null);
        UserPointCardEntity card2 = sampleCard(USER_ID, null);
        List<UUID> cardIds = List.of(card1.getId(), card2.getId());
        given(cardRepository.findAllById(any())).willReturn(List.of(card1, card2));
        given(groupRepository.save(any(PointCardGroupEntity.class)))
                .willAnswer(inv -> {
                    PointCardGroupEntity g = inv.getArgument(0);
                    g.setId(UUID.randomUUID());
                    return g;
                });
        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(any()))
                .willReturn(List.of());

        CreateGroupRequest req = new CreateGroupRequest("セット", null, cardIds);
        groupService.createGroup(USER_ID, req);

        // アイテム一括保存が呼ばれること
        verify(itemRepository).saveAll(any());
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_GROUP_CREATED.name()),
                eq(USER_ID), any(), any(), any(),
                any(), any(), any(), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).contains("\"card_count\":2");
    }

    @Test
    @DisplayName("createGroup: 50 個上限超過で GROUP_LIMIT_EXCEEDED (409)")
    void createGroup_overLimit_throwsGroupLimitExceeded() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(groupRepository.countByUserId(USER_ID)).willReturn(50L);

        CreateGroupRequest req = new CreateGroupRequest("追加", null, null);

        assertThatThrownBy(() -> groupService.createGroup(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.GROUP_LIMIT_EXCEEDED);

        verify(groupRepository, never()).save(any());
    }

    @Test
    @DisplayName("createGroup: cardIds 21 件で GROUP_ITEM_LIMIT_EXCEEDED (409)")
    void createGroup_tooManyCardIds_throwsGroupItemLimitExceeded() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(groupRepository.countByUserId(USER_ID)).willReturn(0L);

        // 21 件の UUID（重複なし）
        List<UUID> cardIds = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            cardIds.add(UUID.randomUUID());
        }

        CreateGroupRequest req = new CreateGroupRequest("特大", null, cardIds);

        assertThatThrownBy(() -> groupService.createGroup(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.GROUP_ITEM_LIMIT_EXCEEDED);

        verify(groupRepository, never()).save(any());
    }

    @Test
    @DisplayName("createGroup: 他人のカード ID 混入で CARD_NOT_FOUND (IDOR 防止)")
    void createGroup_otherUsersCard_throwsCardNotFound() {
        willDoNothing().given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        given(groupRepository.countByUserId(USER_ID)).willReturn(0L);

        UserPointCardEntity own = sampleCard(USER_ID, null);
        UserPointCardEntity stranger = sampleCard(OTHER_USER_ID, null); // 他人のカード
        List<UUID> cardIds = List.of(own.getId(), stranger.getId());
        given(cardRepository.findAllById(any())).willReturn(List.of(own, stranger));

        CreateGroupRequest req = new CreateGroupRequest("混入", null, cardIds);

        assertThatThrownBy(() -> groupService.createGroup(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);

        verify(groupRepository, never()).save(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("createGroup: 規約未同意は WALLET_NOT_ENABLED を伝播")
    void createGroup_termsNotAccepted_propagatesWalletNotEnabled() {
        willThrow(new BusinessException(PointCardErrorCode.WALLET_NOT_ENABLED))
                .given(userSettingsService).assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);

        CreateGroupRequest req = new CreateGroupRequest("名", null, null);

        assertThatThrownBy(() -> groupService.createGroup(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.WALLET_NOT_ENABLED);

        verify(groupRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────
    // updateGroup
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("updateGroup: cardIds 差し替え（既存削除 → 新規挿入）")
    void updateGroup_replaceCardIds_deletesAndInserts() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));

        UserPointCardEntity card1 = sampleCard(USER_ID, null);
        UserPointCardEntity card2 = sampleCard(USER_ID, null);
        List<UUID> newIds = List.of(card1.getId(), card2.getId());
        given(cardRepository.findAllById(any())).willReturn(List.of(card1, card2));
        given(groupRepository.save(any(PointCardGroupEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(any()))
                .willReturn(List.of());

        UpdateGroupRequest req = new UpdateGroupRequest(null, null, null, newIds);
        groupService.updateGroup(group.getId(), USER_ID, req);

        // 既存アイテム削除 + 新規 INSERT
        verify(itemRepository).deleteAllByGroupId(group.getId());
        verify(itemRepository).saveAll(any());
        // 更新では監査ログ発火しない
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("updateGroup: 他人のグループ ID は CARD_NOT_FOUND (IDOR 防止)")
    void updateGroup_otherUser_throwsNotFound() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.findByIdAndUserId(groupId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        UpdateGroupRequest req = new UpdateGroupRequest("x", null, null, null);

        assertThatThrownBy(() -> groupService.updateGroup(groupId, OTHER_USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    @Test
    @DisplayName("updateGroup: cardIds 21 件指定で GROUP_ITEM_LIMIT_EXCEEDED")
    void updateGroup_tooManyCardIds_throwsItemLimit() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));

        List<UUID> cardIds = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            cardIds.add(UUID.randomUUID());
        }
        UpdateGroupRequest req = new UpdateGroupRequest(null, null, null, cardIds);

        assertThatThrownBy(() -> groupService.updateGroup(group.getId(), USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.GROUP_ITEM_LIMIT_EXCEEDED);

        verify(itemRepository, never()).deleteAllByGroupId(any());
    }

    // ─────────────────────────────────────────────
    // deleteGroup
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("deleteGroup: 本人グループ削除 + 監査ログ POINT_CARD_GROUP_DELETED 記録")
    void deleteGroup_owner_deletesAndLogs() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));

        groupService.deleteGroup(group.getId(), USER_ID);

        verify(groupRepository).delete(group);
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_GROUP_DELETED.name()),
                eq(USER_ID), any(), any(), any(),
                any(), any(), any(), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).contains(group.getId().toString());
    }

    @Test
    @DisplayName("deleteGroup: 他人グループは CARD_NOT_FOUND + 監査ログ記録なし")
    void deleteGroup_otherUser_throwsNotFoundNoLog() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.findByIdAndUserId(groupId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.deleteGroup(groupId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);

        verify(groupRepository, never()).delete(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // startPresentation
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("startPresentation: POINT_CARD_VIEWED 監査ログを 1 件だけ記録（個別カードでは発火しない）")
    void startPresentation_recordsViewedOncePerGroup() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));

        // 生体認証要求なしの設定（F18 §9.6 デフォルト状態）
        given(userSettingsService.getOrCreateSettings(USER_ID))
                .willReturn(new PointCardUserSettingsResponse(true, null, "v1.0.0", false));

        // アイテム 3 件、うち 2 件は provider マッチ、1 件は非マッチ
        UUID providerId = UUID.randomUUID();
        PointCardProviderEntity provider = sampleProvider();
        provider.setId(providerId);
        UserPointCardEntity card1 = sampleCard(USER_ID, providerId);
        UserPointCardEntity card2 = sampleCard(USER_ID, providerId);
        UserPointCardEntity card3 = sampleCard(USER_ID, null);

        PointCardGroupItemEntity item1 = PointCardGroupItemEntity.builder()
                .groupId(group.getId()).cardId(card1.getId()).displayOrder(0).build();
        PointCardGroupItemEntity item2 = PointCardGroupItemEntity.builder()
                .groupId(group.getId()).cardId(card2.getId()).displayOrder(1).build();
        PointCardGroupItemEntity item3 = PointCardGroupItemEntity.builder()
                .groupId(group.getId()).cardId(card3.getId()).displayOrder(2).build();
        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(group.getId()))
                .willReturn(List.of(item1, item2, item3));
        given(cardRepository.findAllById(any())).willReturn(List.of(card1, card2, card3));
        given(providerRepository.findAllById(any())).willReturn(List.of(provider));

        GroupDetailResponse res = groupService.startPresentation(group.getId(), USER_ID);

        assertThat(res.items()).hasSize(3);
        assertThat(res.items().get(0).providerMatched()).isTrue();
        assertThat(res.items().get(2).providerMatched()).isFalse();

        // POINT_CARD_VIEWED が 1 件だけ。card_count=3 が記録される
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_VIEWED.name()),
                eq(USER_ID), any(), any(), any(),
                any(), any(), any(), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).contains("\"card_count\":3");
        assertThat(metadataCaptor.getValue()).contains(group.getId().toString());
        // 暗号化対象は metadata に絶対含まれない
        assertThat(metadataCaptor.getValue()).doesNotContain("1234567890123");
        assertThat(metadataCaptor.getValue()).doesNotContain("東急ポイント");
    }

    @Test
    @DisplayName("startPresentation: 他人グループは CARD_NOT_FOUND（POINT_CARD_VIEWED 発火せず）")
    void startPresentation_otherUser_noViewedAudit() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.findByIdAndUserId(groupId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.startPresentation(groupId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);

        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────
    // F18 提示モード追加保護（設計書 §9.6 / POINT_CARD_009）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("startPresentation: require_biometric_on_show=true かつ未認証で POINT_CARD_009 を投擲")
    void startPresentation_requireBiometric_notVerified_throwsBiometricRequired() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));
        given(userSettingsService.getOrCreateSettings(USER_ID))
                .willReturn(new PointCardUserSettingsResponse(true, null, "v1.0.0", true));
        given(authWebAuthnService.isReauthenticatedRecently(USER_ID)).willReturn(false);

        assertThatThrownBy(() -> groupService.startPresentation(group.getId(), USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.BIOMETRIC_REQUIRED);

        // 監査ログは発火しない（提示モードに進めていないため）
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
        // consume は呼ばれない
        verify(authWebAuthnService, never()).consumeReauthentication(any());
    }

    @Test
    @DisplayName("startPresentation: require_biometric_on_show=true かつ認証済みなら通過し consume が呼ばれる")
    void startPresentation_requireBiometric_verified_consumesFlag() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));
        given(userSettingsService.getOrCreateSettings(USER_ID))
                .willReturn(new PointCardUserSettingsResponse(true, null, "v1.0.0", true));
        given(authWebAuthnService.isReauthenticatedRecently(USER_ID)).willReturn(true);
        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(group.getId()))
                .willReturn(List.of());

        // When
        GroupDetailResponse res = groupService.startPresentation(group.getId(), USER_ID);

        // Then: 監査ログ発火 + フラグ消費 + 結果取得
        assertThat(res).isNotNull();
        verify(authWebAuthnService).consumeReauthentication(USER_ID);
        verify(auditLogService).record(
                eq(AuditEventType.POINT_CARD_VIEWED.name()),
                eq(USER_ID), any(), any(), any(),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("startPresentation: require_biometric_on_show=false なら WebAuthn を呼ばない")
    void startPresentation_biometricNotRequired_skipsWebAuthnCheck() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));
        given(userSettingsService.getOrCreateSettings(USER_ID))
                .willReturn(new PointCardUserSettingsResponse(true, null, "v1.0.0", false));
        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(group.getId()))
                .willReturn(List.of());

        groupService.startPresentation(group.getId(), USER_ID);

        verify(authWebAuthnService, never()).isReauthenticatedRecently(any());
        verify(authWebAuthnService, never()).consumeReauthentication(any());
    }

    // ─────────────────────────────────────────────
    // 一覧 / 詳細
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("listMyGroups: グループ件数 + カード件数を返す（カード詳細は含まない）")
    void listMyGroups_returnsListWithCardCount() {
        PointCardGroupEntity g1 = sampleGroup(USER_ID);
        PointCardGroupEntity g2 = sampleGroup(USER_ID);
        given(groupRepository.findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(USER_ID))
                .willReturn(List.of(g1, g2));
        given(itemRepository.countByGroupId(g1.getId())).willReturn(3L);
        given(itemRepository.countByGroupId(g2.getId())).willReturn(0L);

        List<GroupListItemResponse> result = groupService.listMyGroups(USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).cardCount()).isEqualTo(3L);
        assertThat(result.get(1).cardCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("listMyGroups: グループ 0 件で空リスト + countByGroupId は呼ばれない")
    void listMyGroups_empty_returnsEmptyList() {
        given(groupRepository.findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(USER_ID))
                .willReturn(List.of());

        List<GroupListItemResponse> result = groupService.listMyGroups(USER_ID);

        assertThat(result).isEmpty();
        verify(itemRepository, never()).countByGroupId(any());
    }

    @Test
    @DisplayName("getGroupDetail: グループ詳細を返す（loadGroupItems は固定 3 SQL = 中間 + カード + プロバイダー）")
    void getGroupDetail_returnsItemsWithFixedSqlCount() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));

        UUID providerId = UUID.randomUUID();
        PointCardProviderEntity provider = sampleProvider();
        provider.setId(providerId);
        UserPointCardEntity card = sampleCard(USER_ID, providerId);
        PointCardGroupItemEntity item = PointCardGroupItemEntity.builder()
                .groupId(group.getId()).cardId(card.getId()).displayOrder(0).build();

        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(group.getId()))
                .willReturn(List.of(item));
        given(cardRepository.findAllById(any())).willReturn(List.of(card));
        given(providerRepository.findAllById(any())).willReturn(List.of(provider));

        GroupDetailResponse res = groupService.getGroupDetail(group.getId(), USER_ID);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).providerCode()).isEqualTo("tokyu_point");
        assertThat(res.items().get(0).barcodeValue()).isEqualTo("1234567890123");

        // SQL 数の検証: 中間 1 回 + カード 1 回 + プロバイダー 1 回 = 3 回（固定）
        verify(itemRepository).findAllByGroupIdOrderByDisplayOrderAsc(group.getId());
        verify(cardRepository).findAllById(any());
        verify(providerRepository).findAllById(any());

        // POINT_CARD_VIEWED は詳細取得では絶対発火しない
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("getGroupDetail: 他人グループは CARD_NOT_FOUND")
    void getGroupDetail_otherUser_throwsNotFound() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.findByIdAndUserId(groupId, OTHER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.getGroupDetail(groupId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    @Test
    @DisplayName("getGroupDetail: provider 未マッチカードは LEFT JOIN 相当で providerMatched=false で返す")
    void getGroupDetail_unmatchedCard_returnsProviderNull() {
        PointCardGroupEntity group = sampleGroup(USER_ID);
        given(groupRepository.findByIdAndUserId(group.getId(), USER_ID))
                .willReturn(Optional.of(group));

        UserPointCardEntity card = sampleCard(USER_ID, null);
        PointCardGroupItemEntity item = PointCardGroupItemEntity.builder()
                .groupId(group.getId()).cardId(card.getId()).displayOrder(0).build();

        given(itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(group.getId()))
                .willReturn(List.of(item));
        given(cardRepository.findAllById(any())).willReturn(List.of(card));

        GroupDetailResponse res = groupService.getGroupDetail(group.getId(), USER_ID);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).providerMatched()).isFalse();
        assertThat(res.items().get(0).providerCode()).isNull();
        // provider 取得は呼ばれない（providerIds 空のため）
        verify(providerRepository, never()).findAllById(any());
    }
}
