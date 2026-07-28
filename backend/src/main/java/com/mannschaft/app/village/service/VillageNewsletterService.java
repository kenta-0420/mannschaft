package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.NewsletterSendLogResponse;
import com.mannschaft.app.village.dto.NewsletterSettingResponse;
import com.mannschaft.app.village.dto.NewsletterSettingUpdateRequest;
import com.mannschaft.app.village.dto.NewsletterSettingsResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import com.mannschaft.app.village.entity.VillageNewsletterSendLogEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.repository.VillageNewsletterOptOutRepository;
import com.mannschaft.app.village.repository.VillageNewsletterRepository;
import com.mannschaft.app.village.repository.VillageNewsletterSendLogRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β-E — 村ニュースレターサービス。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>村単位の WEEKLY / MONTHLY 設定 CRUD（HEADMAN / ELDER のみ更新可）</li>
 *   <li>ユーザー単位の opt-out / opt-in 切替（村人なら誰でも）</li>
 *   <li>配信履歴の取得</li>
 * </ul>
 *
 * <h2>マスター裁可（2026-05-14）</h2>
 * <p>デフォルトは <b>opt-in</b>（村人は何もしなくても受信対象）。
 * このサービスでは opt-out レコードの「存在 = 受信しない」と扱う。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: 受信者の user_id は他ドメイン参照だが FK は張らない。</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる。
 *       実際の通知配信（NotificationDispatchService 呼び出し）は別バッチ側で
 *       TODO コメント付きの非同期処理として扱う。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageNewsletterService {

    private final VillageNewsletterRepository newsletterRepository;
    private final VillageNewsletterOptOutRepository optOutRepository;
    private final VillageNewsletterSendLogRepository sendLogRepository;
    private final VillageRepository villageRepository;
    private final AuditLogService auditLogService;
    // ②-4 堅牢性（AC-15/16）: HEADMAN/ELDER 認可述語を掲示板認可サービスへ集約（重複ロジック解消）。
    private final VillageBulletinAccessService bulletinAccessService;

    // ====================================================================
    // 設定取得
    // ====================================================================

    /**
     * 村のニュースレター設定を取得する。村人/非村人を問わず閲覧可。
     *
     * <p>requesterUserId が null の場合は opt-out 状態は常に false として返す。</p>
     */
    public NewsletterSettingsResponse getNewsletterSettings(UUID villageId, Long requesterUserId) {
        requireExistingVillage(villageId);
        List<VillageNewsletterEntity> settings =
                newsletterRepository.findByVillageIdAndDeletedAtIsNull(villageId);
        boolean optedOut = requesterUserId != null
                && optOutRepository.existsByVillageIdAndUserId(villageId, requesterUserId);
        List<NewsletterSettingResponse> dtos = settings.stream()
                .map(VillageNewsletterService::toSettingResponse)
                .toList();
        return NewsletterSettingsResponse.builder()
                .villageId(villageId)
                .settings(dtos)
                .optedOut(optedOut)
                .build();
    }

    // ====================================================================
    // 設定 upsert（HEADMAN / ELDER のみ）
    // ====================================================================

    /**
     * 村のニュースレター設定を upsert する（HEADMAN / ELDER のみ）。
     */
    @Transactional
    public NewsletterSettingResponse updateNewsletterSettings(
            UUID villageId,
            NewsletterSettingUpdateRequest request,
            Long actorUserId) {
        requireExistingVillage(villageId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);

        VillageNewsletterEntity entity = newsletterRepository
                .findByVillageIdAndFrequencyAndDeletedAtIsNull(villageId, request.frequency())
                .orElseGet(() -> VillageNewsletterEntity.builder()
                        .villageId(villageId)
                        .frequency(request.frequency())
                        .isEnabled(request.isEnabled())
                        .build());
        entity.setIsEnabled(request.isEnabled());
        VillageNewsletterEntity saved = newsletterRepository.save(entity);
        log.info("ニュースレター設定更新: villageId={} frequency={} enabled={} actorUserId={}",
                villageId, request.frequency(), request.isEnabled(), actorUserId);
        return toSettingResponse(saved);
    }

    // ====================================================================
    // opt-out / opt-in
    // ====================================================================

    /**
     * 当該ユーザーをニュースレターから opt-out する。
     * 既に opt-out 済みの場合は VILLAGE_079。
     */
    @Transactional
    public void optOut(UUID villageId, Long userId) {
        requireExistingVillage(villageId);
        // 村人でなくても opt-out レコードを作ること自体は許容する（再参加時に維持）。
        if (optOutRepository.existsByVillageIdAndUserId(villageId, userId)) {
            throw new BusinessException(VillageErrorCode.NEWSLETTER_ALREADY_OPTED_OUT);
        }
        optOutRepository.save(VillageNewsletterOptOutEntity.builder()
                .villageId(villageId)
                .userId(userId)
                .build());
        auditLogService.record(
                AuditEventType.VILLAGE_NEWSLETTER_OPT_OUT.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"userId\":" + userId + "}"
        );
        log.info("ニュースレター opt-out: villageId={} userId={}", villageId, userId);
    }

    /**
     * 当該ユーザーの opt-out を解除する（= opt-in に戻す）。
     * opt-out レコードが無い場合は VILLAGE_080。
     */
    @Transactional
    public void optIn(UUID villageId, Long userId) {
        requireExistingVillage(villageId);
        VillageNewsletterOptOutEntity entity = optOutRepository
                .findByVillageIdAndUserId(villageId, userId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NEWSLETTER_NOT_OPTED_OUT));
        optOutRepository.delete(entity);
        log.info("ニュースレター opt-in 復帰: villageId={} userId={}", villageId, userId);
    }

    // ====================================================================
    // 配信履歴
    // ====================================================================

    /**
     * 指定村×頻度のニュースレター配信履歴を取得する。
     * newsletter が未作成の場合は空リストを返す。
     */
    public List<NewsletterSendLogResponse> listSendLogs(
            UUID villageId,
            VillageNewsletterFrequency frequency) {
        requireExistingVillage(villageId);
        return newsletterRepository
                .findByVillageIdAndFrequencyAndDeletedAtIsNull(villageId, frequency)
                .map(n -> sendLogRepository.findByNewsletterIdOrderBySentAtDesc(n.getId()))
                .orElseGet(List::of)
                .stream()
                .map(VillageNewsletterService::toSendLogResponse)
                .toList();
    }

    // ====================================================================
    // ガード
    // ====================================================================

    /** 村が存在し凍結されていないことを検証する。IDOR 対策で 404 統一。 */
    private VillageEntity requireExistingVillage(UUID villageId) {
        VillageEntity entity = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (entity.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        return entity;
    }

    // ====================================================================
    // マッパ
    // ====================================================================

    private static NewsletterSettingResponse toSettingResponse(VillageNewsletterEntity e) {
        return NewsletterSettingResponse.builder()
                .id(e.getId())
                .villageId(e.getVillageId())
                .frequency(e.getFrequency())
                .isEnabled(Boolean.TRUE.equals(e.getIsEnabled()))
                .lastSentAt(e.getLastSentAt())
                .nextScheduledAt(e.getNextScheduledAt())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .version(e.getVersion() == null ? 0L : e.getVersion())
                .build();
    }

    private static NewsletterSendLogResponse toSendLogResponse(VillageNewsletterSendLogEntity e) {
        return NewsletterSendLogResponse.builder()
                .id(e.getId())
                .newsletterId(e.getNewsletterId())
                .sentAt(e.getSentAt())
                .recipientCount(e.getRecipientCount() == null ? 0 : e.getRecipientCount())
                .successCount(e.getSuccessCount() == null ? 0 : e.getSuccessCount())
                .failureCount(e.getFailureCount() == null ? 0 : e.getFailureCount())
                .build();
    }
}
