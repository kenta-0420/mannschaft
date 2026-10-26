package com.mannschaft.app.common.storage.quota;

/**
 * F13 ストレージクォータの機能種別（{@code storage_usage_logs.feature_type} に対応）。
 *
 * <p>各機能の R2 アップロード経路から {@link StorageQuotaService#recordUpload} を呼ぶ際に
 * 渡す。Phase 4-α で {@link #PERSONAL_TIMETABLE_NOTES} と {@link #SCHEDULE_MEDIA} を追加した。</p>
 *
 * <p><b>注意:</b> プロフィールメディア（F01.6）は数MBレベルのアイコン・バナーのため
 * クォータ対象外であり、本 enum には含めない。</p>
 *
 * @see <a href="../../../../../../../docs/cross-cutting/storage_quota.md">設計書 §11</a>
 */
public enum StorageFeatureType {
    /** F05.5 ファイル共有 */
    FILE_SHARING,
    /** F05.2 回覧板 */
    CIRCULATION,
    /** F05.1 掲示板 */
    BULLETIN,
    /** F04.2 チャット */
    CHAT,
    /** F04.1 タイムライン */
    TIMELINE,
    /** F06.1 CMS / ブログ */
    CMS,
    /** F06.2 メンバー紹介ギャラリー */
    GALLERY,
    /** F03.15 個人時間割メモ添付（Phase 4-α 追加） */
    PERSONAL_TIMETABLE_NOTES,
    /** F03.14 スケジュールメディア（Phase 4-α 追加） */
    SCHEDULE_MEDIA
}
