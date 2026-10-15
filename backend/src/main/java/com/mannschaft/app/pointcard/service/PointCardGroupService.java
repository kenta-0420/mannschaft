package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.AuthWebAuthnService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.CreateGroupRequest;
import com.mannschaft.app.pointcard.dto.GroupDetailResponse;
import com.mannschaft.app.pointcard.dto.GroupItemResponse;
import com.mannschaft.app.pointcard.dto.GroupListItemResponse;
import com.mannschaft.app.pointcard.dto.PointCardUserSettingsResponse;
import com.mannschaft.app.pointcard.dto.UpdateGroupRequest;
import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;
import com.mannschaft.app.pointcard.entity.PointCardGroupItemEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardGroupItemRepository;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — グループ機能サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.3 / §5.4 / §6 (Groups API) / §11
 *
 * <h2>責務</h2>
 * <ul>
 *   <li>グループ CRUD（50 個上限 + 20 枚上限 + IDOR 防止 + 規約検証）</li>
 *   <li>提示モード開始（{@code POINT_CARD_VIEWED} 監査ログを 1 件のみ記録）</li>
 *   <li>グループ詳細取得（N+1 回避: 中間テーブル全件 + カード一括 + プロバイダー一括）</li>
 * </ul>
 *
 * <h2>監査ログ方針</h2>
 * <p>{@code POINT_CARD_VIEWED} はグループ提示モード起動時のみ 1 件記録する。
 * 個別カード詳細取得（{@code GET /point-cards/{id}}）では発火しない（設計書 §11.3）。
 * metadata には {@code group_id} と {@code card_count} のみ含め、暗号化対象は絶対に含めない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointCardGroupService {

    /** グループ作成上限（設計書 §6.2）。 */
    public static final int GROUP_LIMIT_PER_USER = 50;

    /** グループ内カード数上限（設計書 §6.2）。 */
    public static final int GROUP_ITEM_LIMIT = 20;

    private final PointCardGroupRepository groupRepository;
    private final PointCardGroupItemRepository itemRepository;
    private final UserPointCardRepository cardRepository;
    private final PointCardProviderRepository providerRepository;
    private final PointCardUserSettingsService userSettingsService;
    private final AuditLogService auditLogService;
    /**
     * F18 提示モード追加保護（設計書 §9.6 / POINT_CARD_009）。
     * {@code require_biometric_on_show=true} のユーザーは WebAuthn 再認証フラグが
     * 5 分以内に立っていなければ提示モードを開始できない。
     */
    private final AuthWebAuthnService authWebAuthnService;

    // ─────────────────────────────────────────────
    // 取得
    // ─────────────────────────────────────────────

    /**
     * 自分のグループ一覧を返す（カード詳細を含まない軽量版）。
     */
    public List<GroupListItemResponse> listMyGroups(Long userId) {
        List<PointCardGroupEntity> groups =
                groupRepository.findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(userId);
        if (groups.isEmpty()) {
            return List.of();
        }
        // カード件数をグループ単位で取得（軽量 COUNT、N+1 でも 50 件以下なので許容）
        return groups.stream()
                .map(g -> GroupListItemResponse.from(g, itemRepository.countByGroupId(g.getId())))
                .toList();
    }

    /**
     * グループ詳細を取得する。
     *
     * <p>N+1 を避けるため:
     * <ol>
     *   <li>グループ本体: 1 SQL ({@code findByIdAndUserId})</li>
     *   <li>中間テーブル: 1 SQL ({@code findAllByGroupIdOrderByDisplayOrderAsc})</li>
     *   <li>カード一覧: 1 SQL ({@code findAllById})</li>
     *   <li>プロバイダー一覧: 1 SQL ({@code findAllById}、provider_id 未設定は除外)</li>
     * </ol>
     * 合計 4 SQL（一覧で 50 アイテムあっても固定 4 本）。LEFT JOIN 風に再構成して
     * provider 未マッチカードも含めて返す。
     *
     * <p>※ 暗号化フィールド（barcode_value 等）は {@code EncryptedStringConverter} が
     * SELECT 時に透過的に復号する。
     */
    public GroupDetailResponse getGroupDetail(UUID groupId, Long userId) {
        PointCardGroupEntity group = groupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));
        List<GroupItemResponse> items = loadGroupItems(group);
        return GroupDetailResponse.from(group, items);
    }

    // ─────────────────────────────────────────────
    // 作成
    // ─────────────────────────────────────────────

    /**
     * グループを新規作成する。
     *
     * <ol>
     *   <li>規約検証</li>
     *   <li>50 個上限チェック → {@code GROUP_LIMIT_EXCEEDED}</li>
     *   <li>{@code cardIds} 指定時は 20 枚上限チェック → {@code GROUP_ITEM_LIMIT_EXCEEDED}</li>
     *   <li>各カードの所有者検証（IDOR 防止 → 不一致は {@code CARD_NOT_FOUND}）</li>
     *   <li>グループ + アイテムを保存</li>
     *   <li>{@code POINT_CARD_GROUP_CREATED} 監査ログ記録</li>
     * </ol>
     */
    @Transactional
    public GroupDetailResponse createGroup(Long userId, CreateGroupRequest req) {
        userSettingsService.assertTermsAcceptedAndCurrent(userId, PointCardUserSettingsService.CURRENT_TERMS_VERSION);

        long currentCount = groupRepository.countByUserId(userId);
        if (currentCount >= GROUP_LIMIT_PER_USER) {
            throw new BusinessException(PointCardErrorCode.GROUP_LIMIT_EXCEEDED);
        }

        // 重複を取り除いたうえで上限チェック
        List<UUID> requestedCardIds = dedupe(req.cardIds());
        if (requestedCardIds.size() > GROUP_ITEM_LIMIT) {
            throw new BusinessException(PointCardErrorCode.GROUP_ITEM_LIMIT_EXCEEDED);
        }

        // 本人のカードか検証（IDOR 防止）— 1 SQL で揃って所有確認
        if (!requestedCardIds.isEmpty()) {
            assertCardsOwnedBy(userId, requestedCardIds);
        }

        PointCardGroupEntity group = PointCardGroupEntity.builder()
                .userId(userId)
                .name(req.name())
                .emoji(req.emoji())
                .displayOrder(0)
                .build();
        PointCardGroupEntity savedGroup = groupRepository.save(group);

        if (!requestedCardIds.isEmpty()) {
            saveItems(savedGroup.getId(), requestedCardIds);
        }

        recordGroupAudit(AuditEventType.POINT_CARD_GROUP_CREATED.name(), userId,
                savedGroup.getId(), requestedCardIds.size());
        log.info("ポイントカードグループを作成しました: userId={}, groupId={}, cardCount={}",
                userId, savedGroup.getId(), requestedCardIds.size());

        return GroupDetailResponse.from(savedGroup, loadGroupItems(savedGroup));
    }

    // ─────────────────────────────────────────────
    // 更新
    // ─────────────────────────────────────────────

    /**
     * グループを部分更新する。
     *
     * <p>{@code cardIds} を送ると既存アイテムを丸ごと差し替える（追加・削除を一度に表現）。
     * 重複は除外し、20 枚を超えた場合は {@code GROUP_ITEM_LIMIT_EXCEEDED} を投げる。
     * 監査ログは記録しない（高頻度更新の爆発防止、設計書 §11.3 と整合）。
     */
    @Transactional
    public GroupDetailResponse updateGroup(UUID groupId, Long userId, UpdateGroupRequest req) {
        PointCardGroupEntity group = groupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        if (req.name() != null) {
            group.setName(req.name());
        }
        if (req.emoji() != null) {
            group.setEmoji(req.emoji());
        }
        if (req.displayOrder() != null) {
            group.setDisplayOrder(req.displayOrder());
        }

        if (req.cardIds() != null) {
            List<UUID> dedupedIds = dedupe(req.cardIds());
            if (dedupedIds.size() > GROUP_ITEM_LIMIT) {
                throw new BusinessException(PointCardErrorCode.GROUP_ITEM_LIMIT_EXCEEDED);
            }
            if (!dedupedIds.isEmpty()) {
                assertCardsOwnedBy(userId, dedupedIds);
            }
            // 既存アイテムを一括削除してから挿入し直す（差し替え）
            itemRepository.deleteAllByGroupId(groupId);
            // ※ JPA 一次キャッシュにアイテムが残らないよう flush は不要。新規 INSERT 側は別 ID。
            if (!dedupedIds.isEmpty()) {
                saveItems(groupId, dedupedIds);
            }
        }

        PointCardGroupEntity saved = groupRepository.save(group);
        return GroupDetailResponse.from(saved, loadGroupItems(saved));
    }

    // ─────────────────────────────────────────────
    // 削除
    // ─────────────────────────────────────────────

    /**
     * グループを削除する。中間テーブルは DDL の ON DELETE CASCADE で連鎖削除される。
     * カード本体は削除しない（他グループに属していたり個別利用するため）。
     */
    @Transactional
    public void deleteGroup(UUID groupId, Long userId) {
        PointCardGroupEntity group = groupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        groupRepository.delete(group);

        recordGroupAudit(AuditEventType.POINT_CARD_GROUP_DELETED.name(), userId, groupId, null);
        log.info("ポイントカードグループを削除しました: userId={}, groupId={}", userId, groupId);
    }

    // ─────────────────────────────────────────────
    // 提示モード
    // ─────────────────────────────────────────────

    /**
     * 提示モードを開始する。グループ詳細を取得し、{@code POINT_CARD_VIEWED} を 1 件だけ
     * 監査ログに記録する。個別カード閲覧では発火しないことが重要（設計書 §11.3）。
     */
    @Transactional
    public GroupDetailResponse startPresentation(UUID groupId, Long userId) {
        PointCardGroupEntity group = groupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        // F18 提示モード追加保護（設計書 §9.6 / POINT_CARD_009）。
        // require_biometric_on_show=true の場合、WebAuthn 再認証フラグの存在を確認する。
        // フラグは 5 分 TTL かつ 1 回限りの使用（再生防止）。
        PointCardUserSettingsResponse settings = userSettingsService.getOrCreateSettings(userId);
        if (settings.requireBiometricOnShow()) {
            if (!authWebAuthnService.isReauthenticatedRecently(userId)) {
                throw new BusinessException(PointCardErrorCode.BIOMETRIC_REQUIRED);
            }
            // 再生攻撃防止のため使い切り
            authWebAuthnService.consumeReauthentication(userId);
        }

        List<GroupItemResponse> items = loadGroupItems(group);

        recordViewedAudit(userId, groupId, items.size());
        log.info("ポイントカードグループ提示モードを開始: userId={}, groupId={}, cardCount={}",
                userId, groupId, items.size());

        return GroupDetailResponse.from(group, items);
    }

    // ─────────────────────────────────────────────
    // 補助メソッド
    // ─────────────────────────────────────────────

    /**
     * グループに紐づく全アイテムをカード + プロバイダー復号値込みで返す。
     * 中間 1 SQL + カード 1 SQL + プロバイダー 1 SQL の計 3 SQL で完結する（N+1 回避）。
     * provider 未マッチカード（providerId=null）は LEFT JOIN 風に provider=null で返す。
     */
    private List<GroupItemResponse> loadGroupItems(PointCardGroupEntity group) {
        List<PointCardGroupItemEntity> items =
                itemRepository.findAllByGroupIdOrderByDisplayOrderAsc(group.getId());
        if (items.isEmpty()) {
            return List.of();
        }

        // カード一括取得
        List<UUID> cardIds = items.stream().map(PointCardGroupItemEntity::getCardId).toList();
        Map<UUID, UserPointCardEntity> cardsById = new HashMap<>();
        for (UserPointCardEntity c : cardRepository.findAllById(cardIds)) {
            // 念のためサニティチェック: グループ所有者と異なるカードは混ぜない
            if (group.getUserId().equals(c.getUserId())) {
                cardsById.put(c.getId(), c);
            }
        }

        // プロバイダー一括取得（null は除外）
        Set<UUID> providerIds = new LinkedHashSet<>();
        for (UserPointCardEntity c : cardsById.values()) {
            if (c.getProviderId() != null) {
                providerIds.add(c.getProviderId());
            }
        }
        Map<UUID, PointCardProviderEntity> providersById = new HashMap<>();
        if (!providerIds.isEmpty()) {
            for (PointCardProviderEntity p : providerRepository.findAllById(providerIds)) {
                providersById.put(p.getId(), p);
            }
        }

        List<GroupItemResponse> result = new ArrayList<>(items.size());
        for (PointCardGroupItemEntity item : items) {
            UserPointCardEntity card = cardsById.get(item.getCardId());
            if (card == null) {
                // データ不整合（CASCADE 抜け等）。提示画面では落としても被害がないためスキップする
                continue;
            }
            PointCardProviderEntity provider = card.getProviderId() != null
                    ? providersById.get(card.getProviderId())
                    : null;
            result.add(new GroupItemResponse(
                    card.getId(),
                    item.getDisplayOrder(),
                    card.getDisplayName(),
                    card.getNickname(),
                    card.getBarcodeValue(),
                    card.getBarcodeFormat(),
                    card.getLast4(),
                    provider != null ? provider.getId() : null,
                    provider != null ? provider.getCode() : null,
                    provider != null ? provider.getDisplayName() : null,
                    provider != null ? provider.getBrandColor() : null,
                    provider != null ? provider.getLogoUrl() : null,
                    provider != null
            ));
        }
        return result;
    }

    /**
     * 指定 ID のカード集合が全て {@code userId} の所有であることを検証する。
     * 1 つでも他人のもの / 存在しない場合は {@code CARD_NOT_FOUND} を投げる（IDOR 防止）。
     */
    private void assertCardsOwnedBy(Long userId, List<UUID> cardIds) {
        Set<UUID> uniqueIds = new HashSet<>(cardIds);
        List<UserPointCardEntity> found = cardRepository.findAllById(uniqueIds);
        if (found.size() != uniqueIds.size()) {
            throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
        }
        for (UserPointCardEntity c : found) {
            if (!userId.equals(c.getUserId())) {
                throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
            }
        }
    }

    /**
     * cardIds リストの null 安全 + 重複除去 + 順序保持（LinkedHashSet → List）。
     */
    private static List<UUID> dedupe(List<UUID> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(cardIds));
    }

    /**
     * グループのアイテムを一括保存する。display_order は配列順に 0,1,2... と振る。
     */
    private void saveItems(UUID groupId, List<UUID> cardIds) {
        List<PointCardGroupItemEntity> entities = new ArrayList<>(cardIds.size());
        for (int i = 0; i < cardIds.size(); i++) {
            entities.add(PointCardGroupItemEntity.builder()
                    .groupId(groupId)
                    .cardId(cardIds.get(i))
                    .displayOrder(i)
                    .build());
        }
        itemRepository.saveAll(entities);
    }

    /**
     * グループ系（GROUP_CREATED / GROUP_DELETED）の監査ログを記録する。
     * metadata に含めるのは group_id と必要に応じて card_count のみ。
     */
    private void recordGroupAudit(String eventType, Long userId, UUID groupId, Integer cardCount) {
        String metadata = cardCount != null
                ? String.format("{\"group_id\":\"%s\",\"card_count\":%d}", groupId, cardCount)
                : String.format("{\"group_id\":\"%s\"}", groupId);
        auditLogService.record(eventType, userId,
                null, null, null,
                null, null, null,
                metadata);
    }

    /**
     * 提示モード開始の監査ログ（{@code POINT_CARD_VIEWED}）を記録する。
     * グループ単位で 1 件のみ。個別カード閲覧では絶対に呼ばないこと（設計書 §11.3）。
     */
    private void recordViewedAudit(Long userId, UUID groupId, int cardCount) {
        String metadata = String.format(
                "{\"group_id\":\"%s\",\"card_count\":%d}", groupId, cardCount);
        auditLogService.record(AuditEventType.POINT_CARD_VIEWED.name(), userId,
                null, null, null,
                null, null, null,
                metadata);
    }

}
