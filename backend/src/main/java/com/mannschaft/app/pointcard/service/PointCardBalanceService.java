package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.BalanceEventRequest;
import com.mannschaft.app.pointcard.dto.BalanceEventResponse;
import com.mannschaft.app.pointcard.entity.PointCardBalanceEventEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BalanceOperationType;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardBalanceEventRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — 残高型 CHARGE / SPENT / REFUND サービス（Phase 3 第二陣 2B）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.1 / §6
 *
 * <h2>責務</h2>
 * <ul>
 *   <li>{@link #charge(Long, UUID, Long, BalanceEventRequest, String, String, String) 入金}
 *       — 6 段検証（認可 / カード存在 / プロバイダー紐付け / 組織所有 / type=BALANCE / active） + 上限ガード</li>
 *   <li>{@link #spend(Long, UUID, Long, BalanceEventRequest, String, String, String) 利用}
 *       — 同 6 段検証 + 残高不足ガード（POINT_CARD_017）</li>
 *   <li>{@link #refund(Long, UUID, Long, BalanceEventRequest, String, String, String) 返金}
 *       — 同 6 段検証 + 元 event 参照検証 + 累計返金額超過ガード（POINT_CARD_020）</li>
 *   <li>{@link #listOrgEvents(Long, Long, UUID, Pageable) 組織内履歴一覧}</li>
 *   <li>{@link #listCardEvents(Long, UUID, Long) 単一カード履歴}</li>
 * </ul>
 *
 * <h2>認可方針</h2>
 * <p>本サービスはすべて組織スコープで、ADMIN または DEPUTY_ADMIN のみ操作可能。
 *
 * <h2>不可分性</h2>
 * <p>各操作メソッドは {@code @Transactional} により {@code balance} 更新と
 * {@code balance_event} 挿入を不可分にする。save 失敗時はトランザクションごとロールバックされる。
 *
 * <h2>監査ログのプライバシー方針</h2>
 * <p>{@code POINT_CARD_BALANCE_CHARGED} / {@code _SPENT} / {@code _REFUNDED} の metadata には
 * 暗号化対象（{@code display_name} / {@code nickname} / {@code barcode_value} / {@code memo} / {@code last4}）を
 * <strong>絶対含めない</strong>。{@code card_id} / {@code provider_id} / {@code amount} /
 * {@code balance_after} / {@code refund_of_event_id} のみを記録する（{@code note} は運営側コメントで暗号化対象外）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointCardBalanceService {

    /** 残高上限（10,000,000 円）。DB CHECK と整合。 */
    private static final BigDecimal BALANCE_UPPER_LIMIT = new BigDecimal("10000000.00");

    /** 残高下限（0 円）。DB CHECK と整合。 */
    private static final BigDecimal BALANCE_LOWER_LIMIT = BigDecimal.ZERO;

    private final UserPointCardRepository cardRepository;
    private final PointCardProviderRepository providerRepository;
    private final PointCardBalanceEventRepository balanceEventRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final AccessControlService accessControlService;

    // ─────────────────────────────────────────────
    // CHARGE: 入金
    // ─────────────────────────────────────────────

    /**
     * 残高を入金する（CHARGE）。{@code balance += amount}、event 挿入、監査ログ。
     *
     * <ol>
     *   <li>共通 6 段検証 ({@link #validateForBalanceOperation(Long, UUID, Long)})</li>
     *   <li>{@code amount} が正であることを確認（0 以下は {@code POINT_CARD_016}）</li>
     *   <li>新残高が上限を超えないか確認（超過は {@code POINT_CARD_018}）</li>
     *   <li>カード残高を更新し event を挿入</li>
     *   <li>監査ログ {@code POINT_CARD_BALANCE_CHARGED}</li>
     * </ol>
     */
    @Transactional
    public BalanceEventResponse charge(Long orgId, UUID cardId, Long userId,
                                       BalanceEventRequest req,
                                       String ipAddress, String userAgent, String sessionHash) {
        ValidatedContext ctx = validateForBalanceOperation(orgId, cardId, userId);

        BigDecimal amount = req.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PointCardErrorCode.BALANCE_DELTA_ZERO);
        }

        BigDecimal current = currentBalance(ctx.card());
        BigDecimal newBalance = current.add(amount);
        if (newBalance.compareTo(BALANCE_UPPER_LIMIT) > 0) {
            throw new BusinessException(PointCardErrorCode.BALANCE_LIMIT_EXCEEDED);
        }

        ctx.card().setBalance(newBalance);
        cardRepository.save(ctx.card());

        PointCardBalanceEventEntity event = PointCardBalanceEventEntity.builder()
                .cardId(cardId)
                .providerId(ctx.provider().getId())
                .organizationId(orgId)
                .operationType(BalanceOperationType.CHARGE)
                .delta(amount)
                .balanceAfter(newBalance)
                .operatedByUserId(userId)
                .note(req.note())
                .build();
        PointCardBalanceEventEntity saved = balanceEventRepository.save(event);

        recordAudit(AuditEventType.POINT_CARD_BALANCE_CHARGED,
                userId, orgId, cardId, ctx.provider().getId(),
                amount, newBalance, null, req.note(),
                ipAddress, userAgent, sessionHash);

        log.info("残高入金: orgId={}, cardId={}, userId={}, amount={}, newBalance={}",
                orgId, cardId, userId, amount, newBalance);

        return BalanceEventResponse.from(saved, ctx.provider(), lookupDisplayName(userId));
    }

    // ─────────────────────────────────────────────
    // SPENT: 利用
    // ─────────────────────────────────────────────

    /**
     * 残高を利用（消費）する（SPENT）。{@code balance -= amount}、event 挿入、監査ログ。
     *
     * <p>残高不足の場合は {@code POINT_CARD_017 INSUFFICIENT_BALANCE} を投擲する
     * （{@code Math.max(0, ...)} のような暗黙のクランプは行わない）。
     */
    @Transactional
    public BalanceEventResponse spend(Long orgId, UUID cardId, Long userId,
                                      BalanceEventRequest req,
                                      String ipAddress, String userAgent, String sessionHash) {
        ValidatedContext ctx = validateForBalanceOperation(orgId, cardId, userId);

        BigDecimal amount = req.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PointCardErrorCode.BALANCE_DELTA_ZERO);
        }

        BigDecimal current = currentBalance(ctx.card());
        BigDecimal newBalance = current.subtract(amount);
        if (newBalance.compareTo(BALANCE_LOWER_LIMIT) < 0) {
            throw new BusinessException(PointCardErrorCode.INSUFFICIENT_BALANCE);
        }

        ctx.card().setBalance(newBalance);
        cardRepository.save(ctx.card());

        // SPENT は event の delta を負値で保存する
        BigDecimal negativeDelta = amount.negate();
        PointCardBalanceEventEntity event = PointCardBalanceEventEntity.builder()
                .cardId(cardId)
                .providerId(ctx.provider().getId())
                .organizationId(orgId)
                .operationType(BalanceOperationType.SPENT)
                .delta(negativeDelta)
                .balanceAfter(newBalance)
                .operatedByUserId(userId)
                .note(req.note())
                .build();
        PointCardBalanceEventEntity saved = balanceEventRepository.save(event);

        recordAudit(AuditEventType.POINT_CARD_BALANCE_SPENT,
                userId, orgId, cardId, ctx.provider().getId(),
                negativeDelta, newBalance, null, req.note(),
                ipAddress, userAgent, sessionHash);

        log.info("残高利用: orgId={}, cardId={}, userId={}, amount={}, newBalance={}",
                orgId, cardId, userId, amount, newBalance);

        return BalanceEventResponse.from(saved, ctx.provider(), lookupDisplayName(userId));
    }

    // ─────────────────────────────────────────────
    // REFUND: 返金
    // ─────────────────────────────────────────────

    /**
     * 返金を記録する（REFUND）。{@code balance += amount}、event 挿入、監査ログ。
     *
     * <p>追加検証:
     * <ul>
     *   <li>{@code refundOfEventId} 必須</li>
     *   <li>元 event が存在し、同じ {@code cardId} に紐付くこと</li>
     *   <li>元 event の {@code operationType == SPENT}（CHARGE / REFUND の返金は不可）</li>
     *   <li>既存返金累計 + 今回の {@code amount} ≤ 元 event の {@code |delta|}</li>
     *   <li>新残高が上限を超えないか</li>
     * </ul>
     */
    @Transactional
    public BalanceEventResponse refund(Long orgId, UUID cardId, Long userId,
                                       BalanceEventRequest req,
                                       String ipAddress, String userAgent, String sessionHash) {
        ValidatedContext ctx = validateForBalanceOperation(orgId, cardId, userId);

        BigDecimal amount = req.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PointCardErrorCode.BALANCE_DELTA_ZERO);
        }

        if (req.refundOfEventId() == null) {
            // 返金は元 event を必須参照とする
            throw new BusinessException(PointCardErrorCode.BALANCE_DELTA_ZERO);
        }

        PointCardBalanceEventEntity original = balanceEventRepository.findById(req.refundOfEventId())
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        // 元 event が同一カードに紐付くこと（IDOR 防止）
        if (!original.getCardId().equals(cardId)) {
            throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
        }

        // 元 event が SPENT 限定（CHARGE / REFUND の返金は不可）
        if (original.getOperationType() != BalanceOperationType.SPENT) {
            throw new BusinessException(PointCardErrorCode.REFUND_EXCEEDS_ORIGINAL);
        }

        // 既存返金累計（同じ refund_of_event_id を持つもの）+ 今回 ≤ 元 SPENT の絶対値
        BigDecimal originalAbs = original.getDelta().abs();
        List<PointCardBalanceEventEntity> prior =
                balanceEventRepository.findByRefundOfEventId(req.refundOfEventId());
        BigDecimal accumulated = prior.stream()
                .map(PointCardBalanceEventEntity::getDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (accumulated.add(amount).compareTo(originalAbs) > 0) {
            throw new BusinessException(PointCardErrorCode.REFUND_EXCEEDS_ORIGINAL);
        }

        BigDecimal current = currentBalance(ctx.card());
        BigDecimal newBalance = current.add(amount);
        if (newBalance.compareTo(BALANCE_UPPER_LIMIT) > 0) {
            throw new BusinessException(PointCardErrorCode.BALANCE_LIMIT_EXCEEDED);
        }

        ctx.card().setBalance(newBalance);
        cardRepository.save(ctx.card());

        PointCardBalanceEventEntity event = PointCardBalanceEventEntity.builder()
                .cardId(cardId)
                .providerId(ctx.provider().getId())
                .organizationId(orgId)
                .operationType(BalanceOperationType.REFUND)
                .delta(amount)
                .balanceAfter(newBalance)
                .refundOfEventId(req.refundOfEventId())
                .operatedByUserId(userId)
                .note(req.note())
                .build();
        PointCardBalanceEventEntity saved = balanceEventRepository.save(event);

        recordAudit(AuditEventType.POINT_CARD_BALANCE_REFUNDED,
                userId, orgId, cardId, ctx.provider().getId(),
                amount, newBalance, req.refundOfEventId(), req.note(),
                ipAddress, userAgent, sessionHash);

        log.info("残高返金: orgId={}, cardId={}, userId={}, amount={}, refundOf={}, newBalance={}",
                orgId, cardId, userId, amount, req.refundOfEventId(), newBalance);

        return BalanceEventResponse.from(saved, ctx.provider(), lookupDisplayName(userId));
    }

    // ─────────────────────────────────────────────
    // 履歴一覧
    // ─────────────────────────────────────────────

    /**
     * 組織配下の残高変動履歴を新着順にページング取得する。
     */
    public Page<BalanceEventResponse> listOrgEvents(Long orgId, Long userId,
                                                    UUID providerIdFilter, Pageable pageable) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        Page<PointCardBalanceEventEntity> page = (providerIdFilter != null)
                ? balanceEventRepository.findByOrganizationIdAndProviderIdOrderByOperatedAtDesc(
                        orgId, providerIdFilter, pageable)
                : balanceEventRepository.findByOrganizationIdOrderByOperatedAtDesc(orgId, pageable);

        return page.map(this::toResponse);
    }

    /**
     * 単一カードの残高変動履歴を新着順に取得する（店主側）。
     *
     * <p>IDOR 防止のため、対象カードのプロバイダーが当該組織発行のものかを必ず検証する。
     */
    public List<BalanceEventResponse> listCardEvents(Long orgId, UUID cardId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        UserPointCardEntity card = cardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        if (card.getProviderId() == null) {
            throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
        }
        PointCardProviderEntity provider = providerRepository.findById(card.getProviderId())
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));
        if (provider.getOrganizationId() == null
                || !provider.getOrganizationId().equals(orgId)) {
            throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
        }

        List<PointCardBalanceEventEntity> events =
                balanceEventRepository.findByCardIdOrderByOperatedAtDesc(cardId);

        if (events.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = new HashSet<>();
        for (PointCardBalanceEventEntity e : events) {
            userIds.add(e.getOperatedByUserId());
        }
        Map<Long, String> displayNameCache = bulkLookupDisplayNames(userIds);

        return events.stream()
                .map(e -> BalanceEventResponse.from(
                        e, provider, displayNameCache.get(e.getOperatedByUserId())))
                .toList();
    }

    // ─────────────────────────────────────────────
    // 共通検証
    // ─────────────────────────────────────────────

    /**
     * 残高操作前の 6 段検証を行い、カードとプロバイダーをコンテキストとして返す。
     *
     * <ol>
     *   <li>認可（ADMIN または DEPUTY_ADMIN）</li>
     *   <li>カード存在（{@code findById}）</li>
     *   <li>プロバイダー紐付け（{@code provider_id IS NULL} なら 012 流用）</li>
     *   <li>プロバイダー取得 + 組織所有（IDOR 防止：他組織は 011）</li>
     *   <li>type 検証（{@code SELF_ISSUED_BALANCE} 以外は 015）</li>
     *   <li>active 検証（{@code active=false} なら 007）</li>
     * </ol>
     */
    private ValidatedContext validateForBalanceOperation(Long orgId, UUID cardId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        UserPointCardEntity card = cardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        if (card.getProviderId() == null) {
            throw new BusinessException(PointCardErrorCode.STAMP_INVALID_PROVIDER);
        }

        PointCardProviderEntity provider = providerRepository.findById(card.getProviderId())
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND));

        if (provider.getOrganizationId() == null
                || !provider.getOrganizationId().equals(orgId)) {
            // IDOR 防止：他組織のプロバイダーは PROVIDER_NOT_OWNED（404）で隠蔽
            throw new BusinessException(PointCardErrorCode.PROVIDER_NOT_OWNED);
        }

        if (provider.getType() != PointCardProviderType.SELF_ISSUED_BALANCE) {
            throw new BusinessException(PointCardErrorCode.BALANCE_INVALID_PROVIDER_TYPE);
        }

        if (!Boolean.TRUE.equals(provider.getActive())) {
            throw new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND);
        }

        return new ValidatedContext(card, provider);
    }

    /**
     * カード現在残高（null は 0 として扱う）。Phase 1 既存カードで balance=null のケースを救う。
     */
    private BigDecimal currentBalance(UserPointCardEntity card) {
        return card.getBalance() != null ? card.getBalance() : BigDecimal.ZERO;
    }

    // ─────────────────────────────────────────────
    // 監査ログ
    // ─────────────────────────────────────────────

    /**
     * 監査ログを書き出す。metadata には暗号化対象を含めない（card_id / provider_id / 金額のみ）。
     */
    private void recordAudit(AuditEventType eventType, Long userId, Long orgId,
                             UUID cardId, UUID providerId,
                             BigDecimal delta, BigDecimal balanceAfter,
                             UUID refundOfEventId, String note,
                             String ipAddress, String userAgent, String sessionHash) {
        StringBuilder sb = new StringBuilder();
        sb.append('{')
                .append("\"card_id\":\"").append(cardId).append("\",")
                .append("\"provider_id\":\"").append(providerId).append("\",")
                .append("\"delta\":\"").append(delta.toPlainString()).append("\",")
                .append("\"balance_after\":\"").append(balanceAfter.toPlainString()).append("\"");
        if (refundOfEventId != null) {
            sb.append(",\"refund_of_event_id\":\"").append(refundOfEventId).append("\"");
        }
        if (note != null && !note.isBlank()) {
            sb.append(",\"note\":\"").append(escape(note)).append("\"");
        }
        sb.append('}');

        auditLogService.record(
                eventType.name(),
                userId,
                null, null, orgId,
                ipAddress, userAgent, sessionHash,
                sb.toString());
    }

    // ─────────────────────────────────────────────
    // 補助メソッド
    // ─────────────────────────────────────────────

    /**
     * Entity → Response への変換（プロバイダー + 操作者を都度解決する単発版）。Page#map で利用。
     * listOrgEvents での N+1 は Phase 3 規模では問題なしと判断。
     */
    private BalanceEventResponse toResponse(PointCardBalanceEventEntity event) {
        PointCardProviderEntity provider =
                providerRepository.findById(event.getProviderId()).orElse(null);
        String operatedByName = lookupDisplayName(event.getOperatedByUserId());
        return BalanceEventResponse.from(event, provider, operatedByName);
    }

    private String lookupDisplayName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse(null);
    }

    private Map<Long, String> bulkLookupDisplayNames(Set<Long> userIds) {
        Map<Long, String> cache = new HashMap<>();
        List<UserEntity> users = userRepository.findAllById(userIds);
        for (UserEntity u : users) {
            cache.put(u.getId(), u.getDisplayName());
        }
        return cache;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ─────────────────────────────────────────────
    // 内部レコード
    // ─────────────────────────────────────────────

    /**
     * 共通検証通過後のカード + プロバイダーコンテキスト。
     */
    private record ValidatedContext(UserPointCardEntity card, PointCardProviderEntity provider) {
    }
}
