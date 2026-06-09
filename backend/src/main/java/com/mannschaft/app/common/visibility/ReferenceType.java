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

    /**
     * お知らせウィジェットフィード（F02.6 / F08.9 P4b ペイウォール連結）。
     *
     * <p>フィード単体の可視性は {@code announcement_feeds.visibility} のロールベース値
     * ({@code PUBLIC}/{@code SUPPORTERS_AND_ABOVE}/{@code MEMBERS_AND_ABOVE})、
     * または F08.9 P4b ペイウォール連結用の {@code CUSTOM} で表現される。
     * {@link com.mannschaft.app.social.announcement.visibility.AnnouncementFeedVisibilityResolver}
     * が {@link com.mannschaft.app.payment.service.PaymentGateService#checkAccess}
     * を経由してペイウォール判定を行う（設計書 F08.9 02 §6）。</p>
     */
    ANNOUNCEMENT_FEED,

    /** ファイル添付 (Phase D / 添付元コンテンツの可視性に従属). */
    FILE_ATTACHMENT,

    /** チーム (Phase D / 既存 enum: {@code TeamEntity.Visibility}). */
    TEAM,

    /** 組織 (Phase D / 既存 enum: {@code OrganizationEntity.Visibility}). */
    ORGANIZATION,

    /**
     * 物件履歴パッケージ (F09.13 Phase 1 / 既存 enum: {@code property.WorkPackageVisibility}).
     *
     * <p>マンション管理組合等の改修・修繕・点検・事故・打合せ等を集約する
     * パッケージ単位の可視性判定に用いる。MEMBERS_MASKED / PUBLIC_MASKED などの
     * マスキング系 visibility は MEMBERS_ONLY / PUBLIC として正規化し、
     * 金額マスキング自体は本基盤外の {@code PropertyWorkPackageMaskingService}
     * で処理する（責務分離）。</p>
     */
    PROPERTY_WORK_PACKAGE,

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
    FOLLOW_LIST,

    // ---------------------------------------------------------------------
    // F09.15 / F09.16 系 UUIDv7 reference 予約 (2026-05-12 S0 で追加)
    // CLAUDE.md 原則 6 により F09.15/16 の新規テーブルは UUIDv7 主キーであり、
    // F00 設計書 §3.4 の F00-A 案（並列カラム追加）に基づき、
    // 本セクションの reference_type は corkboard_cards.reference_id_uuid を使う。
    //
    // 規約: 「使用カラム」マッピング表 (本 enum コメント内が真値)
    //   - 上方 (BLOG_POST 〜 FOLLOW_LIST): reference_id (BIGINT) を使う
    //   - 下方 (SUCCESSION_* / RESIDENT_*): reference_id_uuid (BINARY(16)) を使う
    //
    // S0 時点では Resolver 未実装。Phase A-2 以降の S1/S2 で本登録する。
    // ContentVisibilityChecker.canView(...) を呼ぶことを ArchUnit ルールで禁止する (§13.5)。
    // ---------------------------------------------------------------------

    /**
     * 区分所有者の事前登録 (F09.15 / S2 で実装予定).
     *
     * <p>「もしもの備え」事前登録レコード。封緘状態 (SEALED / UNSEAL_REQUESTED /
     * UNSEALED / RE_SEALED) に応じて状態遷移ベースで可視性を判定する。
     * Resolver 未実装のため S0 時点では fail-closed。
     *
     * <p>使用カラム: {@code corkboard_cards.reference_id_uuid} (UUIDv7)
     */
    SUCCESSION_PRE_REGISTRATION,

    /**
     * 入居時誓約 (F09.15 / S1 で実装予定).
     *
     * <p>SUCCESSION_PRE_REGISTRATION / PRIVACY_CONSENT / MONITORING_CONSENT の
     * 3 種誓約を保存する succession_covenants テーブルのレコード可視性。
     * S0 時点では Resolver 未実装。
     *
     * <p>使用カラム: {@code corkboard_cards.reference_id_uuid} (UUIDv7)
     */
    SUCCESSION_COVENANTS,

    /**
     * 居住実態スナップショット (F09.16 / S3〜S4 で実装予定).
     *
     * <p>resident_activity_snapshots テーブルの推定スコア・最終活動日時等の
     * メタデータ可視性。本人 + ADMIN + WATCHER のみ閲覧可能。
     * S0 時点では Resolver 未実装。
     *
     * <p>使用カラム: {@code corkboard_cards.reference_id_uuid} (UUIDv7)
     */
    RESIDENT_ACTIVITY_SNAPSHOT,

    // ---------------------------------------------------------------------
    // F08.10 試合記録・分析 (matches は UUIDv7 主キー)
    // ---------------------------------------------------------------------

    /**
     * 試合 (F08.10 / {@code MatchVisibilityResolver} が実装).
     *
     * <p>{@code matches} テーブル（UUIDv7 / BINARY(16) 主キー）の可視性判定に用いる。
     * 閲覧可視性は独自述語を書かず {@code MatchVisibilityResolver} 経由で F00 正準に委譲する
     * （03_permissions_and_recording_modes.md §C.3.2）。{@link #idKind()} は {@link IdKind#UUID_V7}。</p>
     *
     * <p><b>コルクボード引用は対象外</b>: match はコルクボード（引用・ピン留め）の対象に含めない方針のため、
     * 引用先 ID を保持する {@code corkboard_cards.reference_id_uuid} カラムを使う用途は当面無い
     * （引用要件が顕在化したら追加する）。本値は {@code ContentVisibilityChecker.canViewUuid} の
     * 単発・バッチ判定経路でのみ参照される。</p>
     */
    MATCH;

    /**
     * 本 reference_type が参照する主キー型を返す。
     *
     * <p>F00 設計書 §3.4 (F00-A 案) に基づくマッピング規約:
     * <ul>
     *   <li>{@link IdKind#BIGINT} — corkboard_cards.reference_id (BIGINT UNSIGNED) を使う</li>
     *   <li>{@link IdKind#UUID_V7} — corkboard_cards.reference_id_uuid (BINARY(16)) を使う</li>
     * </ul>
     *
     * <p>Resolver 実装側はこの判定により、機能側 Repository に Long ID を渡すか
     * UUID ID を渡すかを切り替える。
     *
     * @return この reference_type が使う主キー型
     */
    public IdKind idKind() {
        return switch (this) {
            case SUCCESSION_PRE_REGISTRATION,
                 SUCCESSION_COVENANTS,
                 RESIDENT_ACTIVITY_SNAPSHOT,
                 MATCH -> IdKind.UUID_V7;
            default -> IdKind.BIGINT;
        };
    }

    /**
     * reference_type が参照する主キー型の区分。
     *
     * <p>F00-A 案（並列カラム追加）の運用規約を enum 値ごとに表現する。
     */
    public enum IdKind {
        /** BIGINT UNSIGNED 主キー (corkboard_cards.reference_id 経路). */
        BIGINT,
        /** UUIDv7 主キー (corkboard_cards.reference_id_uuid 経路). */
        UUID_V7
    }
}
