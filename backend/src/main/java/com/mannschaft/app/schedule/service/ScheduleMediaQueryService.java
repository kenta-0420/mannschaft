package com.mannschaft.app.schedule.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.acl.StorageAccessService;
import com.mannschaft.app.common.storage.acl.StorageAclDownloadRequest;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.schedule.dto.ScheduleMediaListResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaPatchRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import com.mannschaft.app.schedule.repository.ScheduleMediaUploadRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * スケジュールメディアの参照・更新・削除・クリーンアップを担当するサービス。
 *
 * <ul>
 *   <li>メディア一覧取得（フィルタ・ページング対応）</li>
 *   <li>メディアメタデータ更新（キャプション・撮影日時・カバー写真・経費証憑）</li>
 *   <li>メディア削除（R2 + DB + クォータ減算）</li>
 *   <li>孤立メディアの日次クリーンアップ</li>
 * </ul>
 *
 * <p>本クラスはリファクタリング第6弾で {@link ScheduleMediaService} から参照・更新・削除系を分離して
 * 抽出したものである。挙動は完全に維持し、{@link ScheduleMediaService} がファサードとして呼び出す。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleMediaQueryService {

    // ==================== 定数 ====================

    private static final java.time.Duration DOWNLOAD_TTL = java.time.Duration.ofMinutes(10);

    /** F13 Phase 4-γ: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "schedule_media_uploads";
    private static final String MANAGE_SCHEDULES = "MANAGE_SCHEDULES";

    // ==================== 依存 ====================

    private final R2StorageService r2StorageService;
    private final ScheduleMediaUploadRepository scheduleMediaUploadRepository;
    private final ScheduleRepository scheduleRepository;
    private final AccessControlService accessControlService;
    /** F13 Phase 4-γ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;
    private final ScheduleMediaAclService mediaAclService;
    private final StorageAccessService storageAccessService;

    // ==================== 公開メソッド ====================

    /**
     * スケジュールのメディア一覧を取得する。
     *
     * @param scheduleId         スケジュール ID
     * @param mediaType          メディア種別フィルタ（null = フィルタなし）
     * @param expenseReceiptOnly true の場合、経費証憑のみ返す
     * @param page               ページ番号（1始まり）
     * @param size               1ページあたりの件数
     * @return メディア一覧レスポンス
     */
    public ScheduleMediaListResponse listMedia(
            Long scheduleId, String mediaType, boolean expenseReceiptOnly,
            int page, int size) {

        // スケジュール存在確認
        var schedule = mediaAclService.requireReadable(scheduleId, SecurityUtils.getCurrentUserId());

        Pageable pageable = PageRequest.of(page - 1, size);

        Page<ScheduleMediaUploadEntity> resultPage;
        if (expenseReceiptOnly) {
            resultPage = scheduleMediaUploadRepository
                    .findByScheduleIdAndIsExpenseReceiptTrueOrderByCreatedAtDesc(scheduleId, pageable);
        } else if (mediaType != null) {
            resultPage = scheduleMediaUploadRepository
                    .findByScheduleIdAndMediaTypeOrderByCreatedAtDesc(
                            scheduleId, mediaType.toUpperCase(), pageable);
        } else {
            resultPage = scheduleMediaUploadRepository
                    .findByScheduleIdOrderByCreatedAtDesc(scheduleId, pageable);
        }

        List<StorageAclDownloadRequest> requests = resultPage.getContent().stream()
                .map(media -> {
                    var target = ScheduleMediaAclService.targetOf(schedule, media);
                    return new StorageAclDownloadRequest(media.getR2Key(), target.scope(), target.parent(), target.binding());
                }).toList();
        var urls = storageAccessService.generateDownloadUrlsForList(requests, DOWNLOAD_TTL);
        List<ScheduleMediaResponse> items = resultPage.getContent().stream()
                .filter(media -> urls.containsKey(media.getR2Key()))
                .map(media -> toResponse(media, urls.get(media.getR2Key())))
                .collect(Collectors.toList());

        return ScheduleMediaListResponse.builder()
                .items(items)
                .totalCount(resultPage.getTotalElements())
                .page(page)
                .size(size)
                .hasNext(resultPage.hasNext())
                .build();
    }

    /**
     * スケジュールメディアのメタデータを更新する。
     * キャプション・撮影日時・カバー写真フラグ・経費証憑フラグを部分更新する。
     *
     * @param scheduleId      スケジュール ID
     * @param mediaId         メディア ID
     * @param requestUserId   リクエストを行うユーザー ID
     * @param req             更新リクエスト
     * @return 更新後のメディアレスポンス
     */
    @Transactional
    public ScheduleMediaResponse updateMedia(
            Long scheduleId, Long mediaId, Long requestUserId, ScheduleMediaPatchRequest req) {

        var schedule = mediaAclService.requireReadable(scheduleId, requestUserId);

        ScheduleMediaUploadEntity entity = scheduleMediaUploadRepository.findById(mediaId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "メディアが見つかりません"));

        // scheduleId の一致確認
        if (!scheduleId.equals(entity.getScheduleId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "指定されたスケジュールにメディアが見つかりません");
        }

        ScheduleEntity schedule = findActiveSchedule(scheduleId);
        boolean isManager = isScheduleMediaManager(schedule, requestUserId, false);
        boolean isSelf = isScheduleMediaOwner(schedule, entity, requestUserId);

        // 更新前に全フィールドの権限を検証し、拒否時の部分更新を防ぐ。
        if (!isSelf && !isManager) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "他のユーザーがアップロードしたメディアは変更できません");
        }
        if (req.getIsCover() != null && !isManager) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "カバー写真の設定は管理者のみ変更できます");
        }
        if (Boolean.FALSE.equals(req.getIsExpenseReceipt()) && !isManager) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "経費証憑フラグの解除は管理者のみ可能です");
        }

        // フィールド更新
        if (req.getCaption() != null) {
            entity.updateCaption(req.getCaption());
        }

        if (req.getTakenAt() != null) {
            entity.updateTakenAt(req.getTakenAt());
        }

        if (req.getIsCover() != null && req.getIsCover()) {
            // カバー写真切り替え（@Transactional で保護）
            markAsCover(scheduleId, entity);
        }

        if (req.getIsExpenseReceipt() != null) {
            entity.updateIsExpenseReceipt(req.getIsExpenseReceipt());
        }

        ScheduleMediaUploadEntity saved = scheduleMediaUploadRepository.save(entity);
        log.info("メディアメタデータ更新: scheduleId={}, mediaId={}, userId={}",
                scheduleId, mediaId, requestUserId);
        var target = ScheduleMediaAclService.targetOf(schedule, saved);
        String url = storageAccessService.generateDownloadUrl(saved.getR2Key(), target.scope(),
                target.parent(), target.binding(), DOWNLOAD_TTL);
        return toResponse(saved, url);
    }

    /**
     * スケジュールメディアを削除する。
     * R2 からファイルを削除し、DB レコードを物理削除する。
     *
     * <p><b>F13 Phase 4-γ</b>: DB 削除完了後に {@link StorageQuotaService#recordDeletion} で
     * 使用量を減算する。スコープはスケジュールに紐付く teamId / organizationId / userId で判定する。</p>
     *
     * @param scheduleId      スケジュール ID
     * @param mediaId         メディア ID
     * @param requestUserId   リクエストを行うユーザー ID
     */
    @Transactional
    public void deleteMedia(Long scheduleId, Long mediaId, Long requestUserId) {
        ScheduleMediaUploadEntity entity = scheduleMediaUploadRepository.findById(mediaId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "メディアが見つかりません"));

        // scheduleId の一致確認
        if (!scheduleId.equals(entity.getScheduleId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "指定されたスケジュールにメディアが見つかりません");
        }

        ScheduleEntity schedule = findActiveSchedule(scheduleId);
        boolean isManager = isScheduleMediaManager(schedule, requestUserId, true);
        boolean isSelf = isScheduleMediaOwner(schedule, entity, requestUserId);
        if (!isSelf && !isManager) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "このメディアを削除する権限がありません");
        }

        long fileSize = entity.getFileSize() != null ? entity.getFileSize() : 0L;

        // R2 からメインファイルを削除（失敗しても続行）
        try {
            r2StorageService.delete(entity.getR2Key());
        } catch (Exception e) {
            log.warn("R2 メインファイル削除に失敗しました（DB 削除は続行）: mediaId={}, key={}",
                    mediaId, entity.getR2Key(), e);
        }

        // サムネイルがあれば削除（失敗しても続行）
        if (entity.getThumbnailR2Key() != null) {
            try {
                r2StorageService.delete(entity.getThumbnailR2Key());
            } catch (Exception e) {
                log.warn("R2 サムネイル削除に失敗しました（DB 削除は続行）: mediaId={}, thumbnailKey={}",
                        mediaId, entity.getThumbnailR2Key(), e);
            }
        }

        scheduleMediaUploadRepository.delete(entity);
        log.info("メディア削除完了: scheduleId={}, mediaId={}, userId={}",
                scheduleId, mediaId, requestUserId);

        // F13 Phase 4-γ: 使用量減算（スコープはスケジュールで判定）
        if (fileSize > 0) {
            ScheduleMediaService.ScopeResolution scope =
                    ScheduleMediaService.resolveScopeFor(schedule, schedule.getUserId());
            storageQuotaService.recordDeletion(
                    scope.scopeType(), scope.scopeId(), fileSize,
                    StorageFeatureType.SCHEDULE_MEDIA,
                    REFERENCE_TYPE, mediaId, requestUserId);
        }
    }

    private ScheduleEntity findActiveSchedule(Long scheduleId) {
        ScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "スケジュールが見つかりません"));
        if (schedule.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "スケジュールが見つかりません");
        }
        return schedule;
    }

    private boolean isScheduleMediaManager(
            ScheduleEntity schedule, Long userId, boolean allowSystemAdmin) {
        if (allowSystemAdmin && accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        if (schedule.isPersonal()) {
            return false;
        }
        Long scopeId = schedule.isTeamScope() ? schedule.getTeamId() : schedule.getOrganizationId();
        String scopeType = schedule.isTeamScope() ? "TEAM" : "ORGANIZATION";
        if (scopeId == null) {
            return false;
        }
        if (accessControlService.isAdmin(userId, scopeId, scopeType)) {
            return true;
        }
        return "DEPUTY_ADMIN".equals(accessControlService.getRoleName(userId, scopeId, scopeType))
                && accessControlService.hasPermission(userId, scopeId, scopeType, MANAGE_SCHEDULES);
    }

    private boolean isScheduleMediaOwner(
            ScheduleEntity schedule, ScheduleMediaUploadEntity media, Long userId) {
        if (schedule.isPersonal()) {
            return userId.equals(schedule.getUserId());
        }
        Long scopeId = schedule.isTeamScope() ? schedule.getTeamId() : schedule.getOrganizationId();
        String scopeType = schedule.isTeamScope() ? "TEAM" : "ORGANIZATION";
        return scopeId != null
                && userId.equals(media.getUploaderId())
                && accessControlService.isMember(userId, scopeId, scopeType);
    }

    /**
     * 孤立メディアのクリーンアップ（日次バッチ）。
     * schedule_id IS NULL かつ 72 時間以上経過したレコードを R2 から削除して物理削除する。
     * スケジュール削除時（ON DELETE SET NULL）によって schedule_id が NULL になったレコードも対象となる。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。孤立した予定メディアの物理削除であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "schedule-media-orphan-cleanup-daily", description = "72 時間以上孤立した schedule メディアを毎日 02:30 に R2 から物理削除する")
    @Scheduled(cron = "0 30 2 * * *")
    // 起動間隔は日次 02:30。1 件ごとに R2 削除が走るため、最悪ケースを 1 件 1 秒 × 数千件と見積もり 1 時間を上限とする。
    @SchedulerLock(name = "scheduleMediaOrphanCleanupDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    @Transactional
    public void cleanupOrphanMedia() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(72);
        List<ScheduleMediaUploadEntity> orphans = scheduleMediaUploadRepository.findOrphanMedia(cutoff);

        for (ScheduleMediaUploadEntity orphan : orphans) {
            try {
                r2StorageService.delete(orphan.getR2Key());
                if (orphan.getThumbnailR2Key() != null) {
                    r2StorageService.delete(orphan.getThumbnailR2Key());
                }
            } catch (Exception e) {
                // R2 削除失敗は警告ログのみ（DB 削除は続行する）
                log.warn("孤立メディアの R2 削除に失敗しました（DB 削除は続行）: mediaId={}, key={}",
                        orphan.getId(), orphan.getR2Key(), e);
            }
        }

        scheduleMediaUploadRepository.deleteAll(orphans);
        log.info("孤立メディアのクリーンアップ完了: 削除件数={}", orphans.size());
    }

    // ==================== プライベートメソッド ====================

    /**
     * カバー写真を切り替える。
     * 同一スケジュールの既存カバー写真（is_cover = TRUE）を FALSE にしてから、
     * 指定エンティティを TRUE に設定する。
     * 呼び出し元の @Transactional で保護されること。
     *
     * @param scheduleId スケジュール ID
     * @param entity     カバー写真に設定するエンティティ
     */
    private void markAsCover(Long scheduleId, ScheduleMediaUploadEntity entity) {
        // 既存のカバー写真を全て FALSE に
        List<ScheduleMediaUploadEntity> currentCovers =
                scheduleMediaUploadRepository.findByScheduleIdAndIsCoverTrue(scheduleId);
        for (ScheduleMediaUploadEntity cover : currentCovers) {
            if (!cover.getId().equals(entity.getId())) {
                cover.updateIsCover(false);
                scheduleMediaUploadRepository.save(cover);
            }
        }
        // 対象を TRUE に
        entity.updateIsCover(true);
        log.info("カバー写真切り替え: scheduleId={}, mediaId={}", scheduleId, entity.getId());
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     *
     * @param entity エンティティ
     * @return レスポンス DTO
     */
    private ScheduleMediaResponse toResponse(ScheduleMediaUploadEntity entity, String url) {

        return ScheduleMediaResponse.builder()
                .id(entity.getId())
                .mediaType(entity.getMediaType())
                .url(url)
                // 派生サムネイルは独立したCLAIMED ACLが登録されるまで配信しない。
                .thumbnailUrl(null)
                .fileName(entity.getFileName())
                .fileSize(entity.getFileSize())
                .caption(entity.getCaption())
                .takenAt(entity.getTakenAt())
                .isCover(entity.getIsCover())
                .isExpenseReceipt(entity.getIsExpenseReceipt())
                .processingStatus(entity.getProcessingStatus())
                .uploaderId(entity.getUploaderId())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
