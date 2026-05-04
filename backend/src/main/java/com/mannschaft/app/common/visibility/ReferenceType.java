package com.mannschaft.app.common.visibility;

/**
 * コルクボード・通知・検索など、横断的に参照されるコンテンツの種別。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §3.3 / §6.1。
 *
 * <p><strong>DB との一致要件</strong>:
 * 既存の {@code corkboard_card_reference} テーブルの {@code reference_type} カラム
 * (VARCHAR(30), F09.8.1) と値を一致させること。
 *
 * <p><strong>値の改廃ポリシー</strong> (設計書 §15 D-12):
 * 本 enum からは <strong>値の削除を禁止する</strong>。deprecated 化のみ許可。
 * 削除すると DB に残存する旧 type 文字列が起動時 {@code valueOf} 失敗 →
 * 過去データすべて fail-closed で利用者実害となるため。
 *
 * <p>新 referenceType 追加手順は §6.3 を参照。
 */
public enum ReferenceType {

    // ---------------------------------------------------------------------
    // Phase 1 対象 (設計書 §3.3 / Phase B〜D で順次サポート)
    // ---------------------------------------------------------------------

    /** ブログ記事 (Phase B / 既存 enum: {@code cms.Visibility}). */
    BLOG_POST,

    /** イベント (Phase B / 既存 enum: {@code event.entity.EventVisibility}). */
    EVENT,

    /** 活動結果 (Phase B / 既存 enum: {@code activity.ActivityVisibility}). */
    ACTIVITY_RESULT,

    /** スケジュール (Phase B / 既存 enum: {@code schedule.ScheduleVisibility}). */
    SCHEDULE,

    /** タイムライン投稿 (Phase B / visibility 概念なし、所属固定). */
    TIMELINE_POST,

    /** チャットメッセージ (Phase B / visibility 概念なし、チャネル所属で判定). */
    CHAT_MESSAGE,

    /** 掲示板スレッド (Phase C / visibility 概念なし、所属固定). */
    BULLETIN_THREAD,

    /** トーナメント (Phase C / 既存 enum: {@code tournament.TournamentVisibility}). */
    TOURNAMENT,

    /** 募集案件 (Phase C / 既存 enum: {@code recruitment.RecruitmentVisibility}). */
    RECRUITMENT_LISTING,

    /** ジョブ投稿 (Phase C / 既存 enum: {@code jobmatching.enums.VisibilityScope}). */
    JOB_POSTING,

    /** アンケート (Phase C / 既存 enum: {@code survey.ResultsVisibility}, CUSTOM 多). */
    SURVEY,

    /** 回覧板ドキュメント (Phase C / 配信先 ACL で判定). */
    CIRCULATION_DOCUMENT,

    /** コメント (Phase C / 親コンテンツの可視性に従属). */
    COMMENT,

    /** 写真アルバム (Phase D / 既存 enum: {@code gallery.AlbumVisibility}). */
    PHOTO_ALBUM,

    /** ファイル添付 (Phase D / 添付元コンテンツの可視性に従属). */
    FILE_ATTACHMENT,

    /** チーム (Phase D / 既存 enum: {@code TeamEntity.Visibility}). */
    TEAM,

    /** 組織 (Phase D / 既存 enum: {@code OrganizationEntity.Visibility}). */
    ORGANIZATION,

    // ---------------------------------------------------------------------
    // Phase 2 予約 (設計書 §3.3)
    // Phase 1 では Resolver 未実装のため fail-closed。
    // ContentVisibilityChecker.canView(...) を呼ぶことを ArchUnit ルールで禁止する (§13.5)。
    // ---------------------------------------------------------------------

    /**
     * 個人時間割 (Phase 2 予約).
     *
     * <p>F03.15 個人時間割が Mention 配信されるユースケース対応。
     * Phase 1 では Resolver 未実装。
     */
    PERSONAL_TIMETABLE,

    /**
     * フォロー一覧 (Phase 2 予約).
     *
     * <p>フォロー一覧自体を corkboard カードとして引用するユースケース対応。
     * Phase 1 では Resolver 未実装。
     */
    FOLLOW_LIST
}
