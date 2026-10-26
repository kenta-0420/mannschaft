package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.StampEventResponse;
import com.mannschaft.app.pointcard.dto.StampRequest;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardStampEventEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.PointCardStampEventRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — スタンプ押印 + 履歴サービス（Phase 2 第二陣 2C）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2 / §12
 *
 * <h2>責務</h2>
 * <ul>
 *   <li>{@link #stamp(Long, UUID, Long, StampRequest, String, String, String) スタンプ押印}
 *       — 7 段検証（認可 / カード存在 / プロバイダー紐付け / 組織所有 / type=STAMP / active / delta 妥当）</li>
 *   <li>{@link #listOrgStamps(Long, Long, UUID, Pageable) 組織内押印履歴一覧}</li>
 *   <li>{@link #listCardStamps(Long, UUID, Long) 単一カード押印履歴}</li>
 * </ul>
 *
 * <h2>認可方針</h2>
 * <p>本サービスはすべて組織スコープ。
 * <ul>
 *   <li>{@link #stamp 押印（stamp）}: ADMIN または {@code POINT_CARD_STAMP_ISSUE} Permission を保有する
 *       DEPUTY_ADMIN のみ可能（F18 Phase 4 第二陣 2B で Permission 駆動化）。</li>
 *   <li>{@link #listOrgStamps 押印履歴閲覧（listOrgStamps / listCardStamps）}: ADMIN または DEPUTY_ADMIN
 *       （閲覧権限は押印権限と別物として扱うため、引き続き {@code checkAdminOrAbove} を使用）。</li>
 * </ul>
 * 顧客本人は対象外（顧客側マイページ用 API は Phase 2 第三陣以降で別途整備）。
 *
 * <h2>監査ログのプライバシー方針</h2>
 * <p>{@code POINT_CARD_STAMP_ISSUED} の metadata には暗号化対象
 * （{@code display_name} / {@code nickname} / {@code barcode_value} / {@code memo} / {@code last4}）を
 * <strong>絶対含めない</strong>。{@code card_id} / {@code provider_id} / {@code delta} /
 * {@code new_stamp_count} のみを記録する（{@code memo} は運営側コメントで暗号化対象外なので含めて良い）。
 *
 * <h2>不可分性</h2>
 * <p>{@link #stamp(Long, UUID, Long, StampRequest, String, String, String)} は
 * {@code @Transactional} により {@code stamp_count} 更新と {@code stamp_event} 挿入を不可分にする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointCardStampService {

    private final UserPointCardRepository cardRepository;
    private final PointCardProviderRepository providerRepository;
    private final PointCardStampEventRepository stampEventRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final AccessControlService accessControlService;

    // ─────────────────────────────────────────────
    // スタンプ押印
    // ─────────────────────────────────────────────

    /**
     * スタンプを押印する（{@code stamp_count} 更新 + 履歴記録 + 監査ログ）。
     *
     * <ol>
     *   <li><strong>認可</strong>: {@code accessControlService.checkAdminOrHasPermission(userId, orgId,
     *       "ORGANIZATION", "POINT_CARD_STAMP_ISSUE")}（F18 Phase 4 第二陣 2B で Permission 駆動化）</li>
     *   <li><strong>カード存在</strong>: {@code findById}（他人のカードでも押印可なので {@code findByIdAndUserId} は使わない）</li>
     *   <li><strong>プロバイダー紐付け</strong>: {@code provider_id IS NULL} なら 012</li>
     *   <li><strong>組織所有</strong>: {@code provider.organization_id != orgId} なら 011（IDOR 防止）</li>
     *   <li><strong>type 検証</strong>: {@code SELF_ISSUED_STAMP} 以外なら 013</li>
     *   <li><strong>active 検証</strong>: {@code active=false} なら 007</li>
     *   <li><strong>delta 検証</strong>: 0 なら 014</li>
     *   <li><strong>更新</strong>: {@code stamp_count += delta}、下限 0 ガード</li>
     *   <li><strong>履歴記録</strong>: {@code point_card_stamp_events} 挿入</li>
     *   <li><strong>監査ログ</strong>: {@code POINT_CARD_STAMP_ISSUED}</li>
     * </ol>
     *
     * @param orgId        対象組織 ID（path 変数）
     * @param cardId       対象カード ID
     * @param userId       操作者ユーザー ID（押印者）
     * @param req          押印リクエスト
     * @param ipAddress    クライアント IP（監査ログ用、null 可）
     * @param userAgent    User-Agent（監査ログ用、null 可）
     * @param sessionHash  セッションハッシュ（監査ログ用、null 可）
     * @return 作成された押印イベントのレスポンス
     */
    @Transactional
    public StampEventResponse stamp(Long orgId, UUID cardId, Long userId, StampRequest req,
                                    String ipAddress, String userAgent, String sessionHash) {
        // 1. 認可（F18 Phase 4 第二陣 2B: ADMIN or POINT_CARD_STAMP_ISSUE を持つ DEPUTY_ADMIN）
        accessControlService.checkAdminOrHasPermission(
                userId, orgId, "ORGANIZATION", "POINT_CARD_STAMP_ISSUE");

        // 2. カード取得（他人のカードでも押印可能なので findByIdAndUserId は使わない）
        UserPointCardEntity card = cardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        // 3. プロバイダー紐付けチェック
        if (card.getProviderId() == null) {
            throw new BusinessException(PointCardErrorCode.STAMP_INVALID_PROVIDER);
        }

        // 4. プロバイダー取得 + 組織所有チェック（IDOR 防止）
        PointCardProviderEntity provider = providerRepository.findById(card.getProviderId())
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND));

        if (provider.getOrganizationId() == null
                || !provider.getOrganizationId().equals(orgId)) {
            // 他組織のプロバイダー or 外部プロバイダー → 2B 予約コード 011 PROVIDER_NOT_OWNED
            // 本陣では 011 番号は 2B 用に予約されているため、ここでは provider_not_found(007) で IDOR 隠蔽
            throw new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND);
        }

        // 5. type 検証
        if (provider.getType() != PointCardProviderType.SELF_ISSUED_STAMP) {
            throw new BusinessException(PointCardErrorCode.STAMP_INVALID_PROVIDER_TYPE);
        }

        // 6. active 検証
        if (!Boolean.TRUE.equals(provider.getActive())) {
            throw new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND);
        }

        // 7. delta=0 拒否
        int delta = req.delta();
        if (delta == 0) {
            throw new BusinessException(PointCardErrorCode.STAMP_DELTA_ZERO);
        }

        // 8. stamp_count 更新（下限 0 ガード）
        int currentCount = card.getStampCount() != null ? card.getStampCount() : 0;
        int newCount = Math.max(0, currentCount + delta);
        card.setStampCount(newCount);
        cardRepository.save(card);

        // 9. 履歴記録
        PointCardStampEventEntity event = PointCardStampEventEntity.builder()
                .cardId(cardId)
                .providerId(card.getProviderId())
                .organizationId(orgId)
                .delta(delta)
                .pressedByUserId(userId)
                .pressedAt(OffsetDateTime.now())
                .memo(req.memo())
                .build();
        PointCardStampEventEntity saved = stampEventRepository.save(event);

        // 10. 監査ログ（暗号化対象は含めない）
        String metadata = String.format(
                "{\"card_id\":\"%s\",\"provider_id\":\"%s\",\"delta\":%d,\"new_stamp_count\":%d,\"memo\":\"%s\"}",
                cardId, card.getProviderId(), delta, newCount, escape(req.memo()));
        auditLogService.record(
                AuditEventType.POINT_CARD_STAMP_ISSUED.name(),
                userId,
                null, null, orgId,
                ipAddress, userAgent, sessionHash,
                metadata);

        log.info("スタンプを押印しました: orgId={}, cardId={}, pressedBy={}, delta={}, newCount={}",
                orgId, cardId, userId, delta, newCount);

        String pressedByName = lookupDisplayName(userId);
        return StampEventResponse.from(saved, provider, pressedByName);
    }

    // ─────────────────────────────────────────────
    // 履歴一覧（組織スコープ）
    // ─────────────────────────────────────────────

    /**
     * 組織配下のスタンプ押印履歴を新着順にページング取得する。
     *
     * <p>{@code providerIdFilter} を指定するとプロバイダー単位に絞り込める。
     * 監査ログは記録しない（閲覧頻度が高く、ノイズになるため）。
     *
     * @param orgId            対象組織 ID
     * @param userId           操作者ユーザー ID
     * @param providerIdFilter プロバイダー絞り込み（null 可）
     * @param pageable         ページ指定
     */
    public Page<StampEventResponse> listOrgStamps(Long orgId, Long userId,
                                                  UUID providerIdFilter, Pageable pageable) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        Page<PointCardStampEventEntity> page = (providerIdFilter != null)
                ? stampEventRepository.findByOrganizationIdAndProviderIdOrderByPressedAtDesc(
                        orgId, providerIdFilter, pageable)
                : stampEventRepository.findByOrganizationIdOrderByPressedAtDesc(orgId, pageable);

        return page.map(this::toResponse);
    }

    /**
     * 単一カードのスタンプ履歴を新着順に取得する（店主側）。
     *
     * <p>IDOR 防止のため、対象カードのプロバイダーが当該組織発行のものか必ず検証する。
     *
     * @param orgId  対象組織 ID
     * @param cardId カード ID
     * @param userId 操作者ユーザー ID
     */
    public List<StampEventResponse> listCardStamps(Long orgId, UUID cardId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        UserPointCardEntity card = cardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        // IDOR 防止: 当該組織所有のプロバイダーに紐付くカードかチェック
        if (card.getProviderId() == null) {
            throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
        }
        PointCardProviderEntity provider = providerRepository.findById(card.getProviderId())
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));
        if (provider.getOrganizationId() == null
                || !provider.getOrganizationId().equals(orgId)) {
            // 他組織のカード → 404 で IDOR 隠蔽
            throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
        }

        List<PointCardStampEventEntity> events =
                stampEventRepository.findByCardIdOrderByPressedAtDesc(cardId);

        if (events.isEmpty()) {
            return List.of();
        }

        // 押印者表示名を一括解決（N+1 緩和）
        Set<Long> userIds = new HashSet<>();
        for (PointCardStampEventEntity e : events) {
            userIds.add(e.getPressedByUserId());
        }
        Map<Long, String> displayNameCache = bulkLookupDisplayNames(userIds);

        return events.stream()
                .map(e -> StampEventResponse.from(
                        e, provider, displayNameCache.get(e.getPressedByUserId())))
                .toList();
    }

    // ─────────────────────────────────────────────
    // 補助メソッド
    // ─────────────────────────────────────────────

    /**
     * Entity → Response への変換（プロバイダー + 押印者を都度解決する単発版）。
     * Page#map で利用する。listOrgStamps では N+1 を許容（Phase 2 規模では問題なし、
     * 大規模化したら ProviderCache + bulk lookup に置き換える）。
     */
    private StampEventResponse toResponse(PointCardStampEventEntity event) {
        PointCardProviderEntity provider =
                providerRepository.findById(event.getProviderId()).orElse(null);
        String pressedByName = lookupDisplayName(event.getPressedByUserId());
        return StampEventResponse.from(event, provider, pressedByName);
    }

    /**
     * 単一ユーザーの表示名を取得する。退会済 / 存在しない場合は null。
     */
    private String lookupDisplayName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse(null);
    }

    /**
     * 複数ユーザーの表示名を一括取得する（N+1 緩和）。
     */
    private Map<Long, String> bulkLookupDisplayNames(Set<Long> userIds) {
        Map<Long, String> cache = new HashMap<>();
        List<UserEntity> users = userRepository.findAllById(userIds);
        for (UserEntity u : users) {
            cache.put(u.getId(), u.getDisplayName());
        }
        return cache;
    }

    /**
     * JSON 文字列の最小エスケープ（バックスラッシュとダブルクォートのみ）。
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
