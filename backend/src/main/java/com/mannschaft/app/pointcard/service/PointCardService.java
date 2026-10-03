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
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — ユーザー保有カード CRUD サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.4 / §7 / §11
 *
 * <h2>責務</h2>
 * <ul>
 *   <li>カード追加（規約検証 + 200 枚上限 + fuzzy match + 暗号化）</li>
 *   <li>一覧・詳細取得（IDOR 防止のため必ず {@code findByIdAndUserId} を使う）</li>
 *   <li>更新・削除・最終利用記録</li>
 *   <li>監査ログ記録（POINT_CARD_CREATED / DELETED）</li>
 * </ul>
 *
 * <h2>監査ログのプライバシー方針</h2>
 * <p>metadata には絶対に暗号化対象（displayName / nickname / barcodeValue / memo / last4）を
 * 含めない。代わりに provider_code・provider_matched・card_id のみを記録する。
 * これにより監査ログ自体から個人情報が漏洩することを防ぐ（§11.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointCardService {

    /**
     * カード保有上限（設計書 §7.4）。1 ユーザーがこの枚数を超えると
     * {@link PointCardErrorCode#CARD_LIMIT_EXCEEDED} を投げる。
     */
    public static final int CARD_LIMIT_PER_USER = 200;

    private final UserPointCardRepository cardRepository;
    private final PointCardProviderRepository providerRepository;
    private final ProviderMatchService providerMatchService;
    private final PointCardUserSettingsService userSettingsService;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // 取得
    // ─────────────────────────────────────────────

    /**
     * 自分のカード一覧を返す。
     *
     * <p>並び順: お気に入り → display_order ASC → created_at DESC。
     * プロバイダー情報は N+1 で取得する（Phase 1 は最大 200 件で許容範囲）。
     */
    public List<UserPointCardListItemResponse> listMyCards(Long userId) {
        List<UserPointCardEntity> cards =
                cardRepository.findByUserIdOrderByFavoriteDescDisplayOrderAscCreatedAtDesc(userId);
        if (cards.isEmpty()) {
            return List.of();
        }

        // プロバイダー ID 重複除去 + 一括取得で N+1 を緩和する
        Map<UUID, PointCardProviderEntity> providerCache = new HashMap<>();
        for (UserPointCardEntity card : cards) {
            UUID pid = card.getProviderId();
            if (pid != null && !providerCache.containsKey(pid)) {
                providerRepository.findById(pid).ifPresent(p -> providerCache.put(pid, p));
            }
        }

        return cards.stream()
                .map(c -> UserPointCardListItemResponse.from(
                        c, c.getProviderId() != null ? providerCache.get(c.getProviderId()) : null))
                .toList();
    }

    /**
     * カード詳細を取得する。所有者でない場合は CARD_NOT_FOUND (404、IDOR 対策)。
     */
    public UserPointCardDetailResponse getCard(UUID cardId, Long userId) {
        UserPointCardEntity card = cardRepository.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));
        PointCardProviderEntity provider = card.getProviderId() != null
                ? providerRepository.findById(card.getProviderId()).orElse(null)
                : null;
        return UserPointCardDetailResponse.from(card, provider);
    }

    // ─────────────────────────────────────────────
    // 作成
    // ─────────────────────────────────────────────

    /**
     * カードを新規追加する。
     *
     * <ol>
     *   <li>規約検証（assertTermsAcceptedAndCurrent）</li>
     *   <li>200 枚上限チェック</li>
     *   <li>fuzzy match で provider_id を解決</li>
     *   <li>last4 を平文で算出</li>
     *   <li>保存 → 監査ログ記録</li>
     * </ol>
     */
    @Transactional
    public UserPointCardDetailResponse createCard(Long userId, CreateUserPointCardRequest req) {
        // 1. 規約検証
        userSettingsService.assertTermsAcceptedAndCurrent(userId, PointCardUserSettingsService.CURRENT_TERMS_VERSION);

        // 2. 上限チェック
        long currentCount = cardRepository.countByUserId(userId);
        if (currentCount >= CARD_LIMIT_PER_USER) {
            throw new BusinessException(PointCardErrorCode.CARD_LIMIT_EXCEEDED);
        }

        // 3. fuzzy match
        Optional<PointCardProviderEntity> matched =
                providerMatchService.matchProvider(req.displayName());
        UUID providerId = matched.map(PointCardProviderEntity::getId).orElse(null);

        // 4. last4 算出（平文）
        String last4 = computeLast4(req.barcodeValue());

        // 5. Entity 構築
        UserPointCardEntity card = UserPointCardEntity.builder()
                .userId(userId)
                .providerId(providerId)
                .displayName(req.displayName())
                .nickname(req.nickname())
                .barcodeValue(req.barcodeValue())
                .barcodeFormat(req.barcodeFormat())
                .last4(last4)
                .memo(req.memo())
                .favorite(Boolean.TRUE.equals(req.favorite()))
                .displayOrder(0)
                .build();

        UserPointCardEntity saved = cardRepository.save(card);

        // 6. 監査ログ（暗号化対象は含めない）
        recordAudit(AuditEventType.POINT_CARD_CREATED.name(), userId, saved.getId(),
                matched.orElse(null));

        log.info("ポイントカードを追加しました: userId={}, cardId={}, providerMatched={}",
                userId, saved.getId(), matched.isPresent());

        return UserPointCardDetailResponse.from(saved, matched.orElse(null));
    }

    // ─────────────────────────────────────────────
    // 更新
    // ─────────────────────────────────────────────

    /**
     * カードを部分更新する（PATCH）。
     *
     * <p>{@code displayName} を変更した場合は provider を再 fuzzy match する。
     * {@code barcodeValue} / {@code barcodeFormat} は本 API では変更しない方針
     * （セキュリティ上、変更したい場合は削除 → 再作成を要求）。
     */
    @Transactional
    public UserPointCardDetailResponse updateCard(UUID cardId, Long userId,
                                                  UpdateUserPointCardRequest req) {
        UserPointCardEntity card = cardRepository.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        boolean displayNameChanged = false;
        if (req.displayName() != null) {
            card.setDisplayName(req.displayName());
            displayNameChanged = true;
        }
        if (req.nickname() != null) {
            card.setNickname(req.nickname());
        }
        if (req.memo() != null) {
            card.setMemo(req.memo());
        }
        if (req.favorite() != null) {
            card.setFavorite(req.favorite());
        }
        if (req.displayOrder() != null) {
            card.setDisplayOrder(req.displayOrder());
        }

        // displayName 変更時は provider 再マッチ
        PointCardProviderEntity provider;
        if (displayNameChanged) {
            Optional<PointCardProviderEntity> matched =
                    providerMatchService.matchProvider(card.getDisplayName());
            card.setProviderId(matched.map(PointCardProviderEntity::getId).orElse(null));
            provider = matched.orElse(null);
        } else {
            provider = card.getProviderId() != null
                    ? providerRepository.findById(card.getProviderId()).orElse(null)
                    : null;
        }

        UserPointCardEntity saved = cardRepository.save(card);
        return UserPointCardDetailResponse.from(saved, provider);
    }

    // ─────────────────────────────────────────────
    // 削除
    // ─────────────────────────────────────────────

    /**
     * カードを物理削除する。IDOR 防止のため必ず本人検証してから削除する。
     */
    @Transactional
    public void deleteCard(UUID cardId, Long userId) {
        UserPointCardEntity card = cardRepository.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        PointCardProviderEntity provider = card.getProviderId() != null
                ? providerRepository.findById(card.getProviderId()).orElse(null)
                : null;

        cardRepository.delete(card);

        recordAudit(AuditEventType.POINT_CARD_DELETED.name(), userId, cardId, provider);
        log.info("ポイントカードを削除しました: userId={}, cardId={}", userId, cardId);
    }

    // ─────────────────────────────────────────────
    // 利用記録
    // ─────────────────────────────────────────────

    /**
     * {@code last_used_at} のみを更新する。
     * 高頻度操作のため監査ログには記録しない（設計書 §11.2 — 「提示頻度の集計は別途」）。
     */
    @Transactional
    public void recordUsed(UUID cardId, Long userId) {
        UserPointCardEntity card = cardRepository.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));
        card.setLastUsedAt(OffsetDateTime.now());
        cardRepository.save(card);
    }

    // ─────────────────────────────────────────────
    // 補助メソッド
    // ─────────────────────────────────────────────

    /**
     * バーコード値の下 4 桁を平文で算出する。4 文字未満なら null。
     */
    static String computeLast4(String barcodeValue) {
        if (barcodeValue == null) {
            return null;
        }
        int len = barcodeValue.length();
        if (len < 4) {
            return null;
        }
        return barcodeValue.substring(len - 4);
    }

    /**
     * 監査ログを記録する。metadata には provider_code / provider_matched / card_id のみを含め、
     * 暗号化対象（barcode_value / display_name / nickname / memo / last4）は絶対に含めない。
     */
    private void recordAudit(String eventType, Long userId, UUID cardId,
                             PointCardProviderEntity provider) {
        String providerCode = provider != null ? provider.getCode() : "(none)";
        boolean providerMatched = provider != null;
        String metadata = String.format(
                "{\"card_id\":\"%s\",\"provider_code\":\"%s\",\"provider_matched\":%s}",
                cardId, escape(providerCode), providerMatched);
        auditLogService.record(
                eventType,
                userId,
                null, null, null,
                null, null, null,
                metadata);
    }

    /**
     * JSON エスケープ最小実装（バックスラッシュとダブルクォートのみ）。
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
