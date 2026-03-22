package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.membership.CardStatus;
import com.mannschaft.app.membership.CheckinType;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.dto.CardStatusResponse;
import com.mannschaft.app.membership.dto.CheckinHistoryResponse;
import com.mannschaft.app.membership.dto.MemberCardDetailResponse;
import com.mannschaft.app.membership.dto.MemberCardListMeta;
import com.mannschaft.app.membership.dto.MemberCardListResponse;
import com.mannschaft.app.membership.dto.MemberCardResponse;
import com.mannschaft.app.membership.dto.QrTokenResponse;
import com.mannschaft.app.membership.dto.RegenerateResponse;
import com.mannschaft.app.membership.dto.VerifyRequest;
import com.mannschaft.app.membership.dto.VerifyResponse;
import com.mannschaft.app.membership.dto.SelfCheckinRequest;
import com.mannschaft.app.membership.dto.SelfCheckinResponse;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import com.mannschaft.app.membership.entity.MemberCardCheckinEntity;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.event.MemberCheckedInEvent;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会員証サービス。会員証のCRUD・QR認証・チェックイン管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberCardService {

    private static final int CHECKIN_COOLDOWN_MINUTES = 5;
    private static final String DEFAULT_CARD_PREFIX = "M";

    private final MemberCardRepository memberCardRepository;
    private final MemberCardCheckinRepository checkinRepository;
    private final CheckinLocationRepository locationRepository;
    private final QrTokenService qrTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final NameResolverService nameResolverService;
    private final AccessControlService accessControlService;

    /**
     * 自分の会員証一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 会員証一覧
     */
    public ApiResponse<List<MemberCardResponse>> getMyCards(Long userId) {
        List<MemberCardEntity> cards = memberCardRepository
                .findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(userId);

        List<MemberCardResponse> responses = cards.stream()
                .map(this::toMemberCardResponse)
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * 会員証詳細を取得する。
     *
     * @param cardId 会員証ID
     * @param userId リクエストユーザーID
     * @return 会員証詳細
     */
    public ApiResponse<MemberCardDetailResponse> getCardDetail(Long cardId, Long userId) {
        MemberCardEntity card = findCardOrThrow(cardId);

        // 本人チェック（TODO: ADMIN権限チェックを実装時に追加）
        if (!card.getUserId().equals(userId)) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_002);
        }

        MemberCardDetailResponse response = new MemberCardDetailResponse(
                card.getId(),
                card.getScopeType().name(),
                buildScopeInfo(card),
                card.getCardNumber(),
                card.getDisplayName(),
                card.getStatus().name(),
                card.getIssuedAt(),
                card.getLastCheckinAt(),
                card.getCheckinCount(),
                0 // TODO: QR再生成回数（audit_logsから集計）
        );

        return ApiResponse.of(response);
    }

    /**
     * QRコード表示用トークンを取得する。
     *
     * @param cardId 会員証ID
     * @param userId リクエストユーザーID
     * @return QRトークン
     */
    public ApiResponse<QrTokenResponse> getQrToken(Long cardId, Long userId) {
        MemberCardEntity card = findCardOrThrow(cardId);

        if (!card.getUserId().equals(userId)) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_002);
        }

        if (card.getStatus() != CardStatus.ACTIVE) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_003);
        }

        String qrToken = qrTokenService.generateMemberCardQrToken(card.getCardCode(), card.getQrSecret());
        String scopeName = resolveScopeName(card);

        QrTokenResponse response = new QrTokenResponse(
                card.getId(),
                qrToken,
                qrTokenService.getExpirySeconds(),
                card.getCardNumber(),
                card.getDisplayName(),
                card.getScopeType().name(),
                scopeName
        );

        return ApiResponse.of(response);
    }

    /**
     * QRコードを再生成する。qr_secretをリセットし、旧QRを無効化する。
     *
     * @param cardId 会員証ID
     * @param userId リクエストユーザーID
     * @return 再生成結果
     */
    @Transactional
    public ApiResponse<RegenerateResponse> regenerateQr(Long cardId, Long userId) {
        MemberCardEntity card = findCardOrThrow(cardId);

        if (!card.getUserId().equals(userId)) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_002);
        }

        // SUPPORTERロール以上であることを検証（GUEST不可）
        if (!accessControlService.hasRoleOrAbove(userId, card.getScopeId(), card.getScopeType().name(), "SUPPORTER")) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_004);
        }

        if (card.getStatus() != CardStatus.ACTIVE) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_003);
        }

        String newSecret = qrTokenService.generateSecret();
        card.regenerateQrSecret(newSecret);
        memberCardRepository.save(card);

        log.info("会員証QR再生成: cardId={}, userId={}", cardId, userId);

        RegenerateResponse response = new RegenerateResponse(
                card.getId(),
                "QRコードを再生成しました。以前のQRコードは無効になりました。",
                LocalDateTime.now()
        );

        return ApiResponse.of(response);
    }

    /**
     * QRスキャン認証（スタッフスキャン型）。
     *
     * @param request  認証リクエスト
     * @param adminUserId スキャンしたADMINのユーザーID
     * @return 認証結果
     */
    @Transactional
    public ApiResponse<VerifyResponse> verify(VerifyRequest request, Long adminUserId) {
        // 1. トークンパース
        QrTokenService.MemberCardTokenPayload payload =
                qrTokenService.parseMemberCardToken(request.getQrToken());
        if (payload == null) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_021);
        }

        // 2. 有効期限チェック
        if (qrTokenService.isTokenExpired(payload)) {
            return ApiResponse.of(VerifyResponse.failure("EXPIRED",
                    "QRコードの有効期限が切れています。会員証画面を再表示してください。"));
        }

        // 3. カード検索
        MemberCardEntity card = memberCardRepository
                .findByCardCodeAndDeletedAtIsNull(payload.cardCode())
                .orElse(null);
        if (card == null) {
            return ApiResponse.of(VerifyResponse.failure("CARD_NOT_FOUND",
                    "QRコードに対応する会員証が見つかりません。"));
        }

        // 4. 署名検証
        if (!qrTokenService.verifyMemberCardSignature(payload, card.getQrSecret())) {
            return ApiResponse.of(VerifyResponse.failure("INVALID_SIGNATURE",
                    "QRコードの署名が不正です。再生成された可能性があります。"));
        }

        // 5. スコープ権限チェック（TODO: ADMINのスコープ所属チェック実装）

        // 6. ステータスチェック
        if (card.getStatus() == CardStatus.SUSPENDED) {
            return ApiResponse.of(VerifyResponse.failure("SUSPENDED",
                    "この会員証は一時停止中です。"));
        }
        if (card.getStatus() == CardStatus.REVOKED) {
            return ApiResponse.of(VerifyResponse.failure("REVOKED",
                    "この会員証は無効化されています。"));
        }

        // 7. 二重スキャン防止
        checkDuplicateCheckin(card.getId());

        // 8. チェックイン記録
        MemberCardCheckinEntity checkin = MemberCardCheckinEntity.builder()
                .memberCardId(card.getId())
                .checkinType(CheckinType.STAFF_SCAN)
                .checkedInBy(adminUserId)
                .checkedInAt(LocalDateTime.now())
                .location(request.getLocation())
                .note(request.getNote())
                .build();
        checkinRepository.save(checkin);

        // 9. denormalize更新
        memberCardRepository.incrementCheckinCount(card.getId());

        // 10. イベント発行
        eventPublisher.publishEvent(new MemberCheckedInEvent(
                card.getId(), card.getUserId(), checkin.getId(),
                CheckinType.STAFF_SCAN, card.getScopeId(), card.getScopeType().name()));

        String scopeName = resolveScopeName(card);

        VerifyResponse.MemberCardInfo cardInfo = new VerifyResponse.MemberCardInfo(
                card.getId(), card.getCardNumber(), card.getDisplayName(),
                card.getStatus().name(), card.getScopeType().name(), scopeName,
                card.getCheckinCount() + 1, LocalDateTime.now()
        );
        VerifyResponse.CheckinInfo checkinInfo = new VerifyResponse.CheckinInfo(
                checkin.getId(), checkin.getCheckedInAt(), checkin.getLocation()
        );

        log.info("QRスキャン認証成功: cardId={}, adminUserId={}", card.getId(), adminUserId);

        return ApiResponse.of(VerifyResponse.success(cardInfo, checkinInfo, card.getIssuedAt()));
    }

    /**
     * セルフチェックインを実行する。
     *
     * @param request リクエスト
     * @param userId  ログインユーザーID
     * @return チェックイン結果
     */
    @Transactional
    public ApiResponse<SelfCheckinResponse> selfCheckin(SelfCheckinRequest request, Long userId) {
        // 1. 拠点トークンパース
        QrTokenService.LocationTokenPayload payload =
                qrTokenService.parseLocationToken(request.getLocationQrToken());
        if (payload == null) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_021);
        }

        // 2. 拠点検索
        CheckinLocationEntity location = locationRepository
                .findByLocationCodeAndDeletedAtIsNull(payload.locationCode())
                .orElse(null);
        if (location == null) {
            return ApiResponse.of(SelfCheckinResponse.failure("INVALID_LOCATION",
                    "拠点QRコードが無効です。"));
        }

        // 3. 拠点署名検証
        if (!qrTokenService.verifyLocationSignature(payload, location.getLocationSecret())) {
            return ApiResponse.of(SelfCheckinResponse.failure("INVALID_LOCATION",
                    "拠点QRコードが無効です。"));
        }

        // 4. 拠点有効チェック
        if (!location.getIsActive()) {
            return ApiResponse.of(SelfCheckinResponse.failure("LOCATION_INACTIVE",
                    "この拠点は現在利用できません。"));
        }

        // 5. ユーザーの会員証を検索
        MemberCardEntity card = memberCardRepository
                .findByUserIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        userId, location.getScopeType(), location.getScopeId())
                .orElse(null);
        if (card == null) {
            return ApiResponse.of(SelfCheckinResponse.failure("NOT_MEMBER",
                    "この施設のメンバーではありません。"));
        }

        // 6. ステータスチェック
        if (card.getStatus() == CardStatus.SUSPENDED) {
            return ApiResponse.of(SelfCheckinResponse.failure("CARD_SUSPENDED",
                    "会員証が一時停止中です。"));
        }
        if (card.getStatus() == CardStatus.REVOKED) {
            return ApiResponse.of(SelfCheckinResponse.failure("CARD_REVOKED",
                    "会員証が無効化されています。"));
        }

        // 7. 二重チェックイン防止
        checkDuplicateCheckin(card.getId());

        // 8. チェックイン記録
        MemberCardCheckinEntity checkin = MemberCardCheckinEntity.builder()
                .memberCardId(card.getId())
                .checkinType(CheckinType.SELF)
                .checkinLocationId(location.getId())
                .checkedInAt(LocalDateTime.now())
                .location(location.getName())
                .build();
        checkinRepository.save(checkin);

        // 9. denormalize更新
        memberCardRepository.incrementCheckinCount(card.getId());

        // 10. イベント発行
        eventPublisher.publishEvent(new MemberCheckedInEvent(
                card.getId(), card.getUserId(), checkin.getId(),
                CheckinType.SELF, card.getScopeId(), card.getScopeType().name()));

        String scopeName = resolveScopeName(card);

        SelfCheckinResponse.CheckinInfo checkinInfo = new SelfCheckinResponse.CheckinInfo(
                checkin.getId(), checkin.getCheckedInAt(), location.getName()
        );
        SelfCheckinResponse.MemberCardInfo cardInfo = new SelfCheckinResponse.MemberCardInfo(
                card.getId(), card.getCardNumber(), card.getScopeType().name(), scopeName
        );

        String message = String.format("チェックインしました。%s %s", scopeName, location.getName());

        log.info("セルフチェックイン成功: cardId={}, locationId={}", card.getId(), location.getId());

        return ApiResponse.of(SelfCheckinResponse.success(checkinInfo, cardInfo, message));
    }

    /**
     * チーム/組織の会員証一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ
     * @param q         検索クエリ
     * @return 会員証一覧とメタ情報
     */
    public Map<String, Object> getScopeMemberCards(ScopeType scopeType, Long scopeId,
                                                    CardStatus status, String q) {
        List<MemberCardEntity> cards;
        if (q != null && !q.isBlank()) {
            cards = memberCardRepository.findByScopeAndStatusWithSearch(scopeType, scopeId, status, q);
        } else {
            cards = memberCardRepository.findByScopeAndStatus(scopeType, scopeId, status);
        }

        List<MemberCardListResponse> responses = cards.stream()
                .map(card -> new MemberCardListResponse(
                        card.getId(), card.getUserId(), card.getCardNumber(),
                        card.getDisplayName(), card.getStatus().name(),
                        card.getIssuedAt(), card.getLastCheckinAt(), card.getCheckinCount()))
                .toList();

        // ステータス別件数
        Map<CardStatus, Long> statusCounts = new HashMap<>();
        statusCounts.put(CardStatus.ACTIVE, 0L);
        statusCounts.put(CardStatus.SUSPENDED, 0L);
        statusCounts.put(CardStatus.REVOKED, 0L);
        memberCardRepository.countByScopeGroupByStatus(scopeType, scopeId)
                .forEach(row -> statusCounts.put((CardStatus) row[0], (Long) row[1]));

        MemberCardListMeta meta = new MemberCardListMeta(
                statusCounts.get(CardStatus.ACTIVE),
                statusCounts.get(CardStatus.SUSPENDED),
                statusCounts.get(CardStatus.REVOKED)
        );

        Map<String, Object> result = new HashMap<>();
        result.put("data", responses);
        result.put("meta", meta);
        return result;
    }

    /**
     * 会員証を一時停止する。
     *
     * @param cardId 会員証ID
     * @return ステータス変更結果
     */
    @Transactional
    public ApiResponse<CardStatusResponse> suspend(Long cardId) {
        MemberCardEntity card = findCardOrThrow(cardId);

        if (card.getStatus() == CardStatus.SUSPENDED) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_016);
        }
        if (card.getStatus() == CardStatus.REVOKED) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_009);
        }

        card.suspend();
        memberCardRepository.save(card);

        log.info("会員証一時停止: cardId={}", cardId);

        return ApiResponse.of(new CardStatusResponse(
                card.getId(), card.getStatus().name(), card.getSuspendedAt()));
    }

    /**
     * 会員証の一時停止を解除する。
     *
     * @param cardId 会員証ID
     * @return ステータス変更結果
     */
    @Transactional
    public ApiResponse<CardStatusResponse> reactivate(Long cardId) {
        MemberCardEntity card = findCardOrThrow(cardId);

        if (card.getStatus() == CardStatus.REVOKED) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_017);
        }
        if (card.getStatus() != CardStatus.SUSPENDED) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_018);
        }

        card.reactivate();
        memberCardRepository.save(card);

        log.info("会員証一時停止解除: cardId={}", cardId);

        return ApiResponse.of(new CardStatusResponse(
                card.getId(), card.getStatus().name(), card.getSuspendedAt()));
    }

    /**
     * チェックイン履歴を取得する。
     *
     * @param cardId 会員証ID
     * @param userId リクエストユーザーID
     * @param from   開始日時
     * @param to     終了日時
     * @return チェックイン履歴
     */
    public ApiResponse<List<CheckinHistoryResponse>> getCheckinHistory(
            Long cardId, Long userId, LocalDateTime from, LocalDateTime to) {
        MemberCardEntity card = findCardOrThrow(cardId);

        // 本人チェック（TODO: ADMIN権限チェック追加）
        if (!card.getUserId().equals(userId)) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_002);
        }

        if (from == null) {
            from = LocalDateTime.now().minusDays(30);
        }
        if (to == null) {
            to = LocalDateTime.now();
        }

        List<MemberCardCheckinEntity> checkins = checkinRepository
                .findByMemberCardIdAndCheckedInAtBetweenOrderByCheckedInAtDesc(cardId, from, to);

        // スタッフIDをバッチ取得（N+1回避）
        List<Long> staffIds = checkins.stream()
                .map(MemberCardCheckinEntity::getCheckedInBy)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, String> staffNames = nameResolverService.resolveUserDisplayNames(staffIds);

        List<CheckinHistoryResponse> responses = checkins.stream()
                .map(c -> new CheckinHistoryResponse(
                        c.getId(),
                        c.getCheckinType().name(),
                        c.getCheckedInAt(),
                        c.getCheckedInBy() != null ? staffNames.get(c.getCheckedInBy()) : null,
                        c.getLocation(),
                        card.getCardNumber(),
                        card.getDisplayName()))
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * チーム全体のチェックイン履歴を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param from      開始日時
     * @param to        終了日時
     * @return チェックイン履歴
     */
    public ApiResponse<List<CheckinHistoryResponse>> getScopeCheckins(
            ScopeType scopeType, Long scopeId, LocalDateTime from, LocalDateTime to) {
        if (from == null) {
            from = LocalDateTime.now().minusDays(30);
        }
        if (to == null) {
            to = LocalDateTime.now();
        }

        List<MemberCardCheckinEntity> checkins = checkinRepository
                .findByScopeAndPeriod(scopeType, scopeId, from, to);

        // スタッフIDをバッチ取得（N+1回避）
        List<Long> staffIds = checkins.stream()
                .map(MemberCardCheckinEntity::getCheckedInBy)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, String> staffNames = nameResolverService.resolveUserDisplayNames(staffIds);

        // カードIDからカード情報を取得するためのマップ
        Map<Long, MemberCardEntity> cardMap = new HashMap<>();

        List<CheckinHistoryResponse> responses = checkins.stream()
                .map(c -> {
                    MemberCardEntity card = cardMap.computeIfAbsent(c.getMemberCardId(),
                            id -> memberCardRepository.findById(id).orElse(null));
                    return new CheckinHistoryResponse(
                            c.getId(),
                            c.getCheckinType().name(),
                            c.getCheckedInAt(),
                            c.getCheckedInBy() != null ? staffNames.get(c.getCheckedInBy()) : null,
                            c.getLocation(),
                            card != null ? card.getCardNumber() : null,
                            card != null ? card.getDisplayName() : null);
                })
                .toList();

        return ApiResponse.of(responses);
    }

    // ========== private methods ==========

    private MemberCardEntity findCardOrThrow(Long cardId) {
        return memberCardRepository.findByIdAndDeletedAtIsNull(cardId)
                .orElseThrow(() -> new BusinessException(MembershipErrorCode.MEMBERSHIP_001));
    }

    private void checkDuplicateCheckin(Long cardId) {
        checkinRepository.findTopByMemberCardIdOrderByCheckedInAtDesc(cardId)
                .ifPresent(lastCheckin -> {
                    LocalDateTime cooldownThreshold = LocalDateTime.now()
                            .minusMinutes(CHECKIN_COOLDOWN_MINUTES);
                    if (lastCheckin.getCheckedInAt().isAfter(cooldownThreshold)) {
                        throw new BusinessException(MembershipErrorCode.MEMBERSHIP_010);
                    }
                });
    }

    private MemberCardResponse toMemberCardResponse(MemberCardEntity card) {
        return new MemberCardResponse(
                card.getId(),
                card.getScopeType().name(),
                buildScopeInfo(card),
                card.getCardNumber(),
                card.getDisplayName(),
                card.getStatus().name(),
                card.getIssuedAt(),
                card.getLastCheckinAt(),
                card.getCheckinCount()
        );
    }

    private MemberCardResponse.ScopeInfo buildScopeInfo(MemberCardEntity card) {
        if (card.getScopeType() == ScopeType.PLATFORM) {
            return null;
        }
        String scopeName = nameResolverService.resolveScopeName(card.getScopeType().name(), card.getScopeId());
        return new MemberCardResponse.ScopeInfo(card.getScopeId(), scopeName);
    }

    private String resolveScopeName(MemberCardEntity card) {
        if (card.getScopeType() == ScopeType.PLATFORM) {
            return "Mannschaft";
        }
        return nameResolverService.resolveScopeName(card.getScopeType().name(), card.getScopeId());
    }
}
