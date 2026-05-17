package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.schedule.dto.ScheduleMediaListResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaPatchRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * スケジュールメディア（写真・動画）アップロード管理サービス（ファサード）。
 *
 * <p>リファクタリング第6弾（2026-05-17）で 664 行のサービスクラスを以下の 2 つに分割した。</p>
 *
 * <ul>
 *   <li>{@link ScheduleMediaUploadService} — アップロード URL 発行（IMAGE / VIDEO / Multipart）</li>
 *   <li>{@link ScheduleMediaQueryService} — 一覧取得・メタデータ更新・削除・孤立メディアクリーンアップ</li>
 * </ul>
 *
 * <p>本クラスはこれら 2 サービスへの委譲のみを行うファサードであり、Controller / 既存テストに対する
 * 公開 API（メソッドシグネチャ・例外条件）は完全に維持する。スコープ解決ロジックは static メソッド
 * {@link #resolveScopeFor(ScheduleEntity, Long)} として両サービスから利用される。</p>
 *
 * <ul>
 *   <li>IMAGE（100MB 以下） → Presigned PUT URL（単発）を発行する。</li>
 *   <li>VIDEO または 100MB 超 → Multipart Upload を開始し uploadId を返す。</li>
 *   <li>カバー写真切り替え・経費証憑フラグ管理を担う。</li>
 *   <li>孤立メディアの日次クリーンアップも担う。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleMediaService {

    // ==================== 依存（分割後の 2 サービス） ====================

    private final ScheduleMediaUploadService uploadService;
    private final ScheduleMediaQueryService queryService;

    // ==================== 公開メソッド（ファサード） ====================

    /**
     * スケジュールに添付するメディアのアップロード URL を発行する。
     * IMAGE（100MB 以下）→ Presigned PUT URL を発行する。
     * VIDEO または 100MB 超 → Multipart Upload を開始し uploadId を返す。
     *
     * <p>実装は {@link ScheduleMediaUploadService#generateUploadUrl} に委譲する。</p>
     *
     * @param scheduleId スケジュール ID
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @return アップロード URL 発行レスポンス
     */
    public ScheduleMediaUploadUrlResponse generateUploadUrl(
            Long scheduleId, Long uploaderId, ScheduleMediaUploadUrlRequest req) {
        return uploadService.generateUploadUrl(scheduleId, uploaderId, req);
    }

    /**
     * スケジュールのメディア一覧を取得する。
     *
     * <p>実装は {@link ScheduleMediaQueryService#listMedia} に委譲する。</p>
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
        return queryService.listMedia(scheduleId, mediaType, expenseReceiptOnly, page, size);
    }

    /**
     * スケジュールメディアのメタデータを更新する。
     * キャプション・撮影日時・カバー写真フラグ・経費証憑フラグを部分更新する。
     *
     * <p>実装は {@link ScheduleMediaQueryService#updateMedia} に委譲する。</p>
     *
     * @param scheduleId      スケジュール ID
     * @param mediaId         メディア ID
     * @param requestUserId   リクエストを行うユーザー ID
     * @param isAdminOrDeputy 管理者または副管理者フラグ
     * @param req             更新リクエスト
     * @return 更新後のメディアレスポンス
     */
    public ScheduleMediaResponse updateMedia(
            Long scheduleId, Long mediaId, Long requestUserId, boolean isAdminOrDeputy,
            ScheduleMediaPatchRequest req) {
        return queryService.updateMedia(scheduleId, mediaId, requestUserId, isAdminOrDeputy, req);
    }

    /**
     * スケジュールメディアを削除する。
     * R2 からファイルを削除し、DB レコードを物理削除する。
     *
     * <p>実装は {@link ScheduleMediaQueryService#deleteMedia} に委譲する。</p>
     *
     * @param scheduleId      スケジュール ID
     * @param mediaId         メディア ID
     * @param requestUserId   リクエストを行うユーザー ID
     * @param isAdminOrDeputy 管理者または副管理者フラグ
     */
    public void deleteMedia(Long scheduleId, Long mediaId, Long requestUserId, boolean isAdminOrDeputy) {
        queryService.deleteMedia(scheduleId, mediaId, requestUserId, isAdminOrDeputy);
    }

    /**
     * 孤立メディアのクリーンアップ（日次バッチ）。
     * schedule_id IS NULL かつ 72 時間以上経過したレコードを R2 から削除して物理削除する。
     * スケジュール削除時（ON DELETE SET NULL）によって schedule_id が NULL になったレコードも対象となる。
     *
     * <p>実装は {@link ScheduleMediaQueryService#cleanupOrphanMedia} に委譲する。
     * Spring の @Scheduled アノテーションは {@link ScheduleMediaQueryService} 側で管理されるため、
     * ファサード側で再宣言する必要はない。</p>
     */
    public void cleanupOrphanMedia() {
        queryService.cleanupOrphanMedia();
    }

    /**
     * スケジュールのスコープを解決する（インスタンスメソッド版）。
     * 既存テストとの互換のために残してある。内部実装は {@link #resolveScopeFor} に委譲する。
     *
     * <ul>
     *     <li>teamId が設定されている場合 → TEAM スコープ</li>
     *     <li>organizationId が設定されている場合 → ORGANIZATION スコープ</li>
     *     <li>それ以外（個人スケジュール） → PERSONAL スコープ（uploaderId を使用）</li>
     * </ul>
     *
     * @param schedule   スケジュールエンティティ
     * @param uploaderId アップロードを行うユーザー ID（PERSONAL フォールバック用）
     * @return 解決済みスコープ
     */
    public ScopeResolution resolveScope(ScheduleEntity schedule, Long uploaderId) {
        return resolveScopeFor(schedule, uploaderId);
    }

    /**
     * スケジュールのスコープを解決する（static 共有版）。
     * 分割後の {@link ScheduleMediaUploadService} / {@link ScheduleMediaQueryService} から呼び出されるため、
     * ロジックを一箇所に集約する。
     *
     * @param schedule   スケジュールエンティティ
     * @param uploaderId アップロードを行うユーザー ID（PERSONAL フォールバック用）
     * @return 解決済みスコープ
     */
    public static ScopeResolution resolveScopeFor(ScheduleEntity schedule, Long uploaderId) {
        if (schedule.getTeamId() != null) {
            return new ScopeResolution(StorageScopeType.TEAM, schedule.getTeamId());
        }
        if (schedule.getOrganizationId() != null) {
            return new ScopeResolution(StorageScopeType.ORGANIZATION, schedule.getOrganizationId());
        }
        // 個人スケジュール
        return new ScopeResolution(StorageScopeType.PERSONAL, uploaderId);
    }

    /** 解決されたストレージスコープ。 */
    public record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}
}
