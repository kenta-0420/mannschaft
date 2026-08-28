package com.mannschaft.app.schedule.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.schedule.dto.ScheduleMediaListResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaPatchRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaResponse;
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

    /** R2 配信 URL プレースホルダーベース */
    private static final String R2_BASE_URL = "https://storage.example.com/";

    /** F13 Phase 4-γ: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "schedule_media_uploads";

    // ==================== 依存 ====================

    private final R2StorageService r2StorageService;
    private final ScheduleMediaUploadRepository scheduleMediaUploadRepository;
    private final ScheduleRepository scheduleRepository;
    /** F13 Phase 4-γ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;

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
        scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "スケジュールが見つかりません"));

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

        List<ScheduleMediaResponse> items = resultPage.getContent().stream()
                .map(this::toResponse)
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
     * @param isAdminOrDeputy 管理者または副管理者フラグ
     * @param req             更新リクエスト
     * @return 更新後のメディアレスポンス
     */
    @Transactional
    public ScheduleMediaResponse updateMedia(
            Long scheduleId, Long mediaId, Long requestUserId, boolean isAdminOrDeputy,
            ScheduleMediaPatchRequest req) {

        ScheduleMediaUploadEntity entity = scheduleMediaUploadRepository.findById(mediaId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "メディアが見つかりません"));

        // scheduleId の一致確認
        if (!scheduleId.equals(entity.getScheduleId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "指定されたスケジュールにメディアが見つかりません");
        }

        // is_cover 変更権限チェック（MEMBER は変更不可）
        if (req.getIsCover() != null && !isAdminOrDeputy) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "カバー写真の設定は管理者のみ変更できます");
        }

        // 他人のメディアを操作する権限チェック
        if (!requestUserId.equals(entity.getUploaderId()) && !isAdminOrDeputy) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "他のユーザーがアップロードしたメディアは変更できません");
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
            if (Boolean.FALSE.equals(req.getIsExpenseReceipt()) && !isAdminOrDeputy) {
                // MEMBER は is_expense_receipt を false に変更不可
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "経費証憑フラグの解除は管理者のみ可能です");
            }
            entity.updateIsExpenseReceipt(req.getIsExpenseReceipt());
        }

        ScheduleMediaUploadEntity saved = scheduleMediaUploadRepository.save(entity);
        log.info("メディアメタデータ更新: scheduleId={}, mediaId={}, userId={}",
                scheduleId, mediaId, requestUserId);
        return toResponse(saved);
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
     * @param isAdminOrDeputy 管理者または副管理者フラグ
     */
    @Transactional
    public void deleteMedia(Long scheduleId, Long mediaId, Long requestUserId, boolean isAdminOrDeputy) {
        ScheduleMediaUploadEntity entity = scheduleMediaUploadRepository.findById(mediaId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "メディアが見つかりません"));

        // scheduleId の一致確認
        if (!scheduleId.equals(entity.getScheduleId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "指定されたスケジュールにメディアが見つかりません");
        }

        // 権限チェック（自分のメディアでない かつ 管理者でない → 403）
        if (!requestUserId.equals(entity.getUploaderId()) && !isAdminOrDeputy) {
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
            scheduleRepository.findById(scheduleId).ifPresent(schedule -> {
                ScheduleMediaService.ScopeResolution scope =
                        ScheduleMediaService.resolveScopeFor(schedule, requestUserId);
                storageQuotaService.recordDeletion(
                        scope.scopeType(), scope.scopeId(), fileSize,
                        StorageFeatureType.SCHEDULE_MEDIA,
                        REFERENCE_TYPE, mediaId, requestUserId);
            });
        }
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
    private ScheduleMediaResponse toResponse(ScheduleMediaUploadEntity entity) {
        String url = R2_BASE_URL + entity.getR2Key();
        String thumbnailUrl = entity.getThumbnailR2Key() != null
                ? R2_BASE_URL + entity.getThumbnailR2Key()
                : null;

        return ScheduleMediaResponse.builder()
                .id(entity.getId())
                .mediaType(entity.getMediaType())
                .url(url)
                .thumbnailUrl(thumbnailUrl)
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
