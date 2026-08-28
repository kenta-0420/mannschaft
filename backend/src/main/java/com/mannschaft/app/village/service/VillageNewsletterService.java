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
    private final VillageAccessGate accessGate;
    private final AuditLogService auditLogService;
    // ②-4 堅牢性（AC-15/16）: HEADMAN/ELDER 認可述語を掲示板認可サービスへ集約（重複ロジック解消）。
    private final VillageBulletinAccessService bulletinAccessService;

    // ====================================================================
    // 設定取得
    // ====================================================================

    /**
     * 村のニュースレター設定を取得する。
     *
     * <p>閲覧認可は村の号一覧・詳細（村史面）と同一の基準に揃える
     * （設計書 {@code F17.1_village_newsletter_content_model.md} §8.1）。すなわち
     * {@link VillageBulletinAccessService#checkVillageBulletinViewAccess} に委譲し、
     * 村の {@code bulletin_visibility} が {@code MEMBERS_ONLY} の場合は現役の村人
     * または SYSTEM_ADMIN のみが閲覧できる。存在しない／削除済みの村は 404 で秘匿する。</p>
     *
     * <p>{@code optedOut} は呼び出しユーザー自身の opt-out 状態のみを返す
     * （他ユーザーの購読状態は返さない）。</p>
     */
    public NewsletterSettingsResponse getNewsletterSettings(UUID villageId, Long requesterUserId) {
        requireExistingVillage(villageId, requesterUserId);
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, requesterUserId);
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
        requireExistingVillage(villageId, actorUserId);
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
        requireExistingVillage(villageId, userId);
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
        requireExistingVillage(villageId, userId);
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
     * 指定村×頻度のニュースレター配信履歴を取得する（村の運営者のみ）。
     *
     * <p>配信履歴は村の運営情報であるため、設計書
     * （{@code F17.1_village_community_phase2_3_api_addendum.md} §1.14）に従い
     * 村の運営者に限定する。判定は {@link VillageBulletinAccessService#requireHeadmanOrElder}
     * に委譲し、現役（退村・BAN 済みでない）の HEADMAN / ELDER のみを通す。
     * 存在しない／削除済みの村は 404 で秘匿する。</p>
     *
     * <p>newsletter が未作成の場合は空リストを返す。</p>
     */
    public List<NewsletterSendLogResponse> listSendLogs(
            UUID villageId,
            VillageNewsletterFrequency frequency,
            Long actorUserId) {
        requireExistingVillage(villageId, actorUserId);
        bulletinAccessService.requireHeadmanOrElder(villageId, actorUserId);
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

    /**
     * 村が存在し、実行者に可視であることを検証する。IDOR 対策で 404 統一。
     *
     * <p>存在確認と可視性判定は {@link VillageAccessGate} に一元化する。従来の実体は
     * 凍結済み村を拒否していなかった（Javadoc の「凍結されていないこと」は実装と食い違っていた）ため、
     * 凍結を 409/404 に倒さない {@link VillageAccessGate#loadVillageAllowingArchived} へ委譲し、
     * 凍結村のニュースレター設定閲覧・opt-out という既存の挙動をそのまま保つ。</p>
     *
     * <p>これにより、後段の {@code checkVillageBulletinViewAccess} /
     * {@code requireHeadmanOrElder}（403）へ進む前に非公開(UNLISTED)村の非村人が
     * 不在と同一の 404 で弾かれ、存在オラクルが塞がる。PUBLIC 村はゲートを素通りする。</p>
     */
    private VillageEntity requireExistingVillage(UUID villageId, Long actorUserId) {
        return accessGate.loadVillageAllowingArchived(villageId, actorUserId);
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
