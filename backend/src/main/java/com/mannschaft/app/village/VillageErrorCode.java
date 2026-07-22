package com.mannschaft.app.village;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F17.1 村機能のエラーコード定義（設計書 §10 準拠）。
 *
 * <p>B2（村 CRUD）/ B3（メンバーシップ）/ B4（ニックネーム）/ B5（村作成申請）の
 * 各足軽が必要とするコードを 1 つの enum に集約する。番号は設計書
 * {@code docs/features/F17.1_village_community.md} §10 に従い VILLAGE_001〜030 を割り当て、
 * 設計書未定義の追加コードは VILLAGE_031〜050 の空き番号に割り振る。</p>
 *
 * <p>HttpStatus マッピングは {@link com.mannschaft.app.common.GlobalExceptionHandler}
 * の {@code ERROR_CODE_STATUS_MAP} で個別指定する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VillageErrorCode implements ErrorCode {

    // ==================================================================
    // 設計書 §10 採番（VILLAGE_001〜030）
    // ==================================================================

    /** VILLAGE_001: 村が存在しない / 削除 / 凍結済み（404、IDOR 対策で統一） */
    VILLAGE_NOT_FOUND("VILLAGE_001", "村が見つかりません", Severity.WARN),

    /** VILLAGE_002: UNLISTED 村に非村人がアクセス（403） */
    VILLAGE_UNLISTED("VILLAGE_002", "この村は非公開です", Severity.WARN),

    /** VILLAGE_003: 村名重複（400） */
    VILLAGE_NAME_TAKEN("VILLAGE_003", "その村名はすでに使われています", Severity.WARN),

    /** VILLAGE_004: スラッグ形式不正（400） */
    VILLAGE_SLUG_INVALID("VILLAGE_004", "スラッグの形式が不正です（3〜40文字の英小文字・数字・ハイフン）", Severity.WARN),

    /** VILLAGE_005: スラッグ重複（400） */
    VILLAGE_SLUG_TAKEN("VILLAGE_005", "そのスラッグはすでに使われています", Severity.WARN),

    /** VILLAGE_006: すでに村人（409） */
    ALREADY_MEMBER("VILLAGE_006", "すでに村人です", Severity.WARN),

    /** VILLAGE_007: 村人ではない（409） */
    NOT_MEMBER("VILLAGE_007", "この村のメンバーではありません", Severity.WARN),

    /** VILLAGE_008: ニックネーム重複（プラットフォーム全体で先着優先・409） */
    NICKNAME_TAKEN("VILLAGE_008", "そのニックネームはすでに使われています", Severity.WARN),

    /** VILLAGE_009: 通報レートリミット超過（429、設計書 §6.4 で 10 件/時/ユーザー） */
    VILLAGE_REPORT_RATE_LIMITED("VILLAGE_009", "通報の上限に達しました（1時間に10件）", Severity.WARN),

    /** VILLAGE_010: 村作成申請レート超過（429） */
    CREATION_REQUEST_THROTTLED("VILLAGE_010", "村作成申請のレート上限に達しました（1日3件・保有10件まで）", Severity.WARN),

    /** VILLAGE_011: ニックネーム変更レート超過（429） */
    NICKNAME_CHANGE_THROTTLED("VILLAGE_011", "ニックネーム変更は月3回までです", Severity.WARN),

    /** VILLAGE_012: 参加村数ハード上限（429） */
    PARTICIPATION_LIMIT_EXCEEDED("VILLAGE_012", "参加可能な村数の上限（100）を超えました", Severity.WARN),

    /** VILLAGE_014: ガイドライン未同意 / 同意期限切れ（400） */
    GUIDELINE_NOT_AGREED("VILLAGE_014", "村ガイドラインへの同意が必要です（直近1時間以内）", Severity.WARN),

    /** VILLAGE_015: チーム/組織代表権限なし（403） */
    REPRESENT_FORBIDDEN("VILLAGE_015", "この主体として参加する権限がありません", Severity.WARN),

    /** VILLAGE_016: 指定主体が村人でない（403） */
    SUBJECT_NOT_MEMBER("VILLAGE_016", "指定された主体は村人ではありません", Severity.WARN),

    /** VILLAGE_017: 村長は後継未指名で退村不可（409） */
    HEADMAN_CANNOT_LEAVE("VILLAGE_017", "村長は後継を指名するまで退村できません", Severity.WARN),

    /** VILLAGE_018: 楽観ロック競合（409） */
    VERSION_CONFLICT("VILLAGE_018", "他のユーザーが情報を更新しました。最新の内容を確認して再度お試しください", Severity.WARN),

    /** VILLAGE_019: APPROVAL 村に直接参加しようとした（409） */
    VILLAGE_JOIN_REQUIRES_APPROVAL("VILLAGE_019", "この村は承認が必要です。参加申請をご利用ください", Severity.WARN),

    /** VILLAGE_022: 新規アカウント（7日以内）が制限操作を行おうとした（403） */
    NEW_ACCOUNT_RESTRICTED("VILLAGE_022", "新規アカウントはこの操作を行えません", Severity.WARN),

    /** VILLAGE_024: モデレーション権限なし（403） — 村長/長老でないユーザーが BAN や役職変更を試みた */
    MODERATION_FORBIDDEN("VILLAGE_024", "この操作を行う権限がありません", Severity.WARN),

    /** VILLAGE_025: 参加/退出のフラッピング検出（409） */
    JOIN_RATE_EXCEEDED("VILLAGE_025", "短時間に参加と退出を繰り返しています。しばらく時間をおいてからお試しください", Severity.WARN),

    /** VILLAGE_026: 通報対象が不正（対象 ID 空・対象種別が当該村に属さない・MEMBERSHIP UUID 不正等／422） */
    VILLAGE_REPORT_INVALID_TARGET("VILLAGE_026", "通報対象が不正です", Severity.WARN),

    /** VILLAGE_027: 凍結済み村への変更操作（409） */
    VILLAGE_ALREADY_ARCHIVED("VILLAGE_027", "この村は凍結されています", Severity.WARN),

    /** VILLAGE_028: ニックネーム長違反 / NG ワード / 使用文字違反（422） */
    NICKNAME_INVALID("VILLAGE_028", "ニックネームが無効です（長さ・禁止語・使用文字を確認してください）", Severity.WARN),

    /** VILLAGE_029: 村説明文・名称・カテゴリ等の入力値不正（400） */
    VILLAGE_FIELD_INVALID("VILLAGE_029", "入力値が不正です", Severity.WARN),

    // ==================================================================
    // 設計書 §10 未定義の追加コード（VILLAGE_031〜050 の空きに割当）
    // ==================================================================

    /** VILLAGE_031: BAN されているメンバーの操作（403） */
    MEMBER_BANNED("VILLAGE_031", "この村から BAN されています", Severity.WARN),

    /** VILLAGE_032: 村作成申請が存在しない（404） */
    CREATION_REQUEST_NOT_FOUND("VILLAGE_032", "村作成申請が見つかりません", Severity.WARN),

    /** VILLAGE_033: 既に審査済みの申請への再操作（409） */
    CREATION_REQUEST_ALREADY_REVIEWED("VILLAGE_033", "この村作成申請は既に審査済みです", Severity.WARN),

    /** VILLAGE_034: 拒否済み申請への操作（403） */
    CREATION_REQUEST_REJECTED("VILLAGE_034", "この村作成申請は拒否済みです", Severity.WARN),

    /** VILLAGE_035: 申請時の slug が既存村と衝突（409） — VILLAGE_005 と区別し申請ライフサイクル専用 */
    CREATION_REQUEST_SLUG_TAKEN("VILLAGE_035", "指定された slug は既に使用されています", Severity.WARN),

    /** VILLAGE_036: 一般ユーザーが OFFICIAL 村を申請しようとした（403） */
    OFFICIAL_VILLAGE_FORBIDDEN("VILLAGE_036", "一般ユーザーは公式村を申請できません", Severity.WARN),

    /** VILLAGE_037: 村作成権限なし（運営権限が必要） — B2 が独自に VILLAGE_028 で定義していたものを移設 */
    VILLAGE_CREATE_FORBIDDEN("VILLAGE_037", "公式村の作成には運営権限が必要です", Severity.WARN),

    // ==================================================================
    // B6 村参加申請（APPROVAL 村）— VILLAGE_038〜041
    // ==================================================================

    /** VILLAGE_038: 参加申請レコードが存在しない（404、IDOR 対策で統一） */
    VILLAGE_JOIN_REQUEST_NOT_FOUND("VILLAGE_038", "参加申請が見つかりません", Severity.WARN),

    /** VILLAGE_039: 同一主体で PENDING 申請が既に存在する（409） */
    VILLAGE_JOIN_REQUEST_PENDING_DUPLICATE("VILLAGE_039", "既に審査待ちの参加申請があります", Severity.WARN),

    /** VILLAGE_040: 既に審査済み（APPROVED/REJECTED/WITHDRAWN）の申請への再操作（409） */
    VILLAGE_JOIN_REQUEST_ALREADY_REVIEWED("VILLAGE_040", "この参加申請は既に処理済みです", Severity.WARN),

    /** VILLAGE_041: FREE 村に対して参加申請 API を使った（422、直接参加 API を使うべき） */
    VILLAGE_FREE_VILLAGE_DIRECT_JOIN("VILLAGE_041", "この村は自由参加です。参加申請ではなく直接参加してください", Severity.WARN),

    // ==================================================================
    // B7 通報・モデレーション — VILLAGE_042/043
    // 設計書 §10 予約の VILLAGE_009（RATE_LIMITED）と VILLAGE_026（INVALID_TARGET）は
    // 上位ブロックに統合済み。ここは追加分のみ。
    // ==================================================================

    /** VILLAGE_042: 通報レコードが存在しない（404、IDOR 対策で 404） */
    VILLAGE_REPORT_NOT_FOUND("VILLAGE_042", "通報が見つかりません", Severity.WARN),

    /** VILLAGE_043: 通報が既に解決済み（409） — 統合採番で 042 から繰下げ */
    VILLAGE_REPORT_ALREADY_RESOLVED("VILLAGE_043", "この通報は既に処理済みです", Severity.WARN),

    // ==================================================================
    // B8 お気に入り村ピン留め（VILLAGE_013 + VILLAGE_044〜047）
    // ==================================================================

    /** VILLAGE_013: ピン上限超過（422、設計書 §10 の予約番号を使用） */
    VILLAGE_PIN_LIMIT_EXCEEDED("VILLAGE_013", "お気に入り村の上限（30件）を超えました", Severity.WARN),

    /** VILLAGE_044: ピンが存在しない（404） */
    VILLAGE_PIN_NOT_FOUND("VILLAGE_044", "お気に入り村のピンが見つかりません", Severity.WARN),

    /** VILLAGE_045: 既にピン留め済み（409） — 統合採番で VILLAGE_046 から繰上げ */
    VILLAGE_PIN_ALREADY_EXISTS("VILLAGE_045", "この村は既にお気に入りに登録されています", Severity.WARN),

    /** VILLAGE_047: 並び替え対象集合の不一致（422、現在のピン集合と orderedVillageIds が一致しない） */
    VILLAGE_PIN_ORDER_MISMATCH("VILLAGE_047", "並び替え対象が現在のお気に入り村と一致しません", Severity.WARN),

    // ==================================================================
    // B9 井戸端会議 + 投稿主体一覧（VILLAGE_048〜050）
    // ==================================================================

    /**
     * VILLAGE_048: 投稿主体権限なし（403）。
     * {@code postedAs} に指定した主体を代表する権限がない、あるいは指定主体が村のメンバーでない場合に投げる。
     * §6.3 なりすまし防止の最終防衛線。— 統合採番で VILLAGE_040 から振替。
     */
    VILLAGE_POSTING_IDENTITY_FORBIDDEN("VILLAGE_048",
            "指定した投稿主体として発言する権限がありません", Severity.WARN),

    /**
     * VILLAGE_049: 村ロビーが見つからない（404）。
     * 村作成バッチ・承認時にチャネル生成されていない異常系の入口エラー。— 統合採番で VILLAGE_041 から振替。
     */
    VILLAGE_LOBBY_NOT_FOUND("VILLAGE_049",
            "村の井戸端会議チャンネルが見つかりません", Severity.WARN),

    /**
     * VILLAGE_050: 村ロビーチャネル初期化失敗（500）。
     * 自動払い出し時の DB 競合・整合性違反などの内部例外。— 統合採番で VILLAGE_042 から振替。
     */
    VILLAGE_LOBBY_CHANNEL_INIT_FAILED("VILLAGE_050",
            "村の井戸端会議チャンネルの初期化に失敗しました", Severity.ERROR),

    // ==================================================================
    // B10 村内検索 + ダッシュボード集約 — VILLAGE_051
    // ==================================================================

    /**
     * VILLAGE_051: 村内検索クエリが不正（422）。
     * 空文字 / 最低 2 文字未満 / 不正な type を指定された場合に投げる（F17.1 §4.12）。
     */
    VILLAGE_SEARCH_INVALID_QUERY("VILLAGE_051",
            "検索キーワードは2文字以上を指定してください", Severity.WARN),

    // ==================================================================
    // F17 Phase 2 U3 — 村代表委任（VILLAGE_052〜055）
    // ==================================================================

    /** VILLAGE_052: 代表委任レコードが存在しない（404、IDOR 対策で 404 統一）。 */
    REPRESENTATIVE_NOT_FOUND("VILLAGE_052",
            "代表委任が見つかりません", Severity.WARN),

    /** VILLAGE_053: 既に現役の代表委任が存在する（409、重複 grant 拒否）。 */
    REPRESENTATIVE_ALREADY_GRANTED("VILLAGE_053",
            "このユーザーには既に代表権が委任されています", Severity.WARN),

    /** VILLAGE_054: 代表委任の対象メンバーシップが TEAM/ORGANIZATION でない（422） */
    REPRESENTATIVE_NOT_TEAM_OR_ORG_MEMBERSHIP("VILLAGE_054",
            "代表委任はチーム/組織メンバーシップに対してのみ可能です", Severity.WARN),

    /** VILLAGE_055: 委任先ユーザーが当該チーム/組織のメンバーでない（422） */
    REPRESENTATIVE_USER_NOT_IN_SUBJECT("VILLAGE_055",
            "委任先ユーザーが対象チーム/組織のメンバーではありません", Severity.WARN),

    // ==================================================================
    // F17 Phase 2 U4 — 歳時記カレンダー（VILLAGE_056〜058）
    // ==================================================================

    /** VILLAGE_056: 歳時記イベントが見つからない（404、IDOR 対策で 404） */
    CALENDAR_EVENT_NOT_FOUND("VILLAGE_056",
            "歳時記イベントが見つかりません", Severity.WARN),

    /** VILLAGE_057: 歳時記イベントの期間が不正（422、event_end_date < event_date） */
    CALENDAR_EVENT_INVALID_DATE_RANGE("VILLAGE_057",
            "終了日は開始日以降を指定してください", Severity.WARN),

    /** VILLAGE_058: 歳時記イベントのカラーコード形式不正（422、#RRGGBB 以外） */
    CALENDAR_EVENT_INVALID_COLOR("VILLAGE_058",
            "色は #RRGGBB 形式で指定してください", Severity.WARN),

    // ==================================================================
    // F17 Phase 2 U5 — お祭り（VILLAGE_059〜062）
    // ==================================================================

    /** VILLAGE_059: お祭りレコードが存在しない（404、IDOR 対策で統一）。 */
    FESTIVAL_NOT_FOUND("VILLAGE_059", "お祭りが見つかりません", Severity.WARN),

    /** VILLAGE_060: 期間が不正（422、ends_at <= starts_at）。 */
    FESTIVAL_INVALID_PERIOD("VILLAGE_060", "お祭りの期間が不正です（終了日時は開始日時より後である必要があります）", Severity.WARN),

    /** VILLAGE_061: テーマ色フォーマット不正（422、#RRGGBB 以外）。 */
    FESTIVAL_INVALID_COLOR("VILLAGE_061", "テーマ色は #RRGGBB 形式で指定してください", Severity.WARN),

    /** VILLAGE_062: 終了済み / 中止済みのお祭りを更新しようとした（409）。 */
    FESTIVAL_ALREADY_ENDED("VILLAGE_062", "このお祭りは既に終了または中止されています", Severity.WARN),

    // ==================================================================
    // F17 Phase 2 U6 — 練習試合・審判募集（VILLAGE_063〜068）
    // ==================================================================

    /** VILLAGE_063: 練習試合募集レコードが存在しない（404、IDOR 対策で統一） */
    MATCH_RECRUIT_NOT_FOUND("VILLAGE_063", "練習試合の募集が見つかりません", Severity.WARN),

    /** VILLAGE_064: OPEN 以外の募集に応募しようとした（409） */
    MATCH_RECRUIT_NOT_OPEN("VILLAGE_064", "この募集は現在受付中ではありません", Severity.WARN),

    /** VILLAGE_065: 試合時刻が不正（match_time_end < match_time_start）（422） */
    MATCH_RECRUIT_TIME_INVALID("VILLAGE_065", "試合時刻の指定が不正です（開始時刻が終了時刻より後）", Severity.WARN),

    /** VILLAGE_066: 応募レコードが存在しない（404、IDOR 対策で統一） */
    MATCH_APPLICATION_NOT_FOUND("VILLAGE_066", "応募が見つかりません", Severity.WARN),

    /** VILLAGE_067: 同一ユーザーで PENDING 応募が既に存在する（409） */
    MATCH_APPLICATION_DUPLICATE("VILLAGE_067", "既に審査待ちの応募があります", Severity.WARN),

    /** VILLAGE_068: 応募レビュー時の status 値が ACCEPTED/REJECTED でない（422） */
    MATCH_APPLICATION_INVALID_STATUS("VILLAGE_068",
            "応募の審査結果は ACCEPTED または REJECTED を指定してください", Severity.WARN),

    // ==================================================================
    // F17 Phase 3-β — 寄合（VILLAGE_069〜074）
    // ==================================================================

    /** VILLAGE_069: 寄合レコードが存在しない（404、IDOR 対策で統一）。 */
    MEETUP_NOT_FOUND("VILLAGE_069", "寄合が見つかりません", Severity.WARN),

    /** VILLAGE_070: 既に CONFIRMED の寄合に対する重複確定操作（409）。 */
    MEETUP_ALREADY_CONFIRMED("VILLAGE_070", "この寄合は既に確定済みです", Severity.WARN),

    /** VILLAGE_071: 寄合の status が想定外（例：CANCELLED に対する update/confirm/vote）（409）。 */
    MEETUP_INVALID_STATUS("VILLAGE_071", "この寄合は現在この操作を受け付けられない状態です", Severity.WARN),

    /** VILLAGE_072: 候補日レコードが存在しない / 指定寄合に属さない（404、IDOR 対策で統一）。 */
    CANDIDATE_DATE_NOT_FOUND("VILLAGE_072", "候補日が見つかりません", Severity.WARN),

    /** VILLAGE_073: 同一候補日への重複追加（409、UNIQUE 制約に先立つアプリ層チェック）。 */
    VOTE_DUPLICATE("VILLAGE_073", "この候補日は既に登録されています", Severity.WARN),

    /** VILLAGE_074: 寄合の操作には村人であることが必要（403）。 */
    MEETUP_NOT_MEMBER("VILLAGE_074", "寄合の操作には村人である必要があります", Severity.WARN),

    // ==================================================================
    // F17 Phase 3-β — 村史（VILLAGE_075）
    // ==================================================================

    /** VILLAGE_075: 指定年月の村史が存在しない（404、IDOR 対策で統一）。 */
    CHRONICLE_NOT_FOUND("VILLAGE_075", "指定された村史が見つかりません", Severity.WARN),

    // ==================================================================
    // F17 Phase 3-β — ご縁スコア（VILLAGE_076）
    // ==================================================================

    /** VILLAGE_076: ご縁スコアレコードが存在しない（404）。 */
    SERENDIPITY_NOT_FOUND("VILLAGE_076", "ご縁スコアが見つかりません", Severity.WARN),

    // ==================================================================
    // F17 Phase 3-β — 巡礼（VILLAGE_077）
    // ==================================================================

    /** VILLAGE_077: 巡礼推薦が見つからない（404、IDOR 対策で統一）。 */
    PILGRIMAGE_NOT_FOUND("VILLAGE_077", "巡礼の推薦が見つかりません", Severity.WARN),

    // ==================================================================
    // F17 Phase 3-β-E — 村ニュースレター（VILLAGE_078〜080）
    // ==================================================================

    /** VILLAGE_078: ニュースレター設定が存在しない（404、IDOR 対策で統一）。 */
    NEWSLETTER_NOT_FOUND("VILLAGE_078",
            "ニュースレター設定が見つかりません", Severity.WARN),

    /** VILLAGE_079: 既に opt-out 済み（409、二重 opt-out 拒否）。 */
    NEWSLETTER_ALREADY_OPTED_OUT("VILLAGE_079",
            "このニュースレターは既に配信停止済みです", Severity.WARN),

    /** VILLAGE_080: opt-out していないのに opt-in しようとした（409、対称性）。 */
    NEWSLETTER_NOT_OPTED_OUT("VILLAGE_080",
            "このニュースレターは配信停止されていません", Severity.WARN),

    // ==================================================================
    // F17.1 村掲示板グローバル方式 — 掲示板閲覧認可（VILLAGE_081）
    // ==================================================================

    /**
     * VILLAGE_081: MEMBERS_ONLY の村掲示板を非メンバーが閲覧しようとした（403）。
     *
     * <p>村本体の {@code bulletin_visibility = MEMBERS_ONLY} の場合、村メンバーまたは
     * SYSTEM_ADMIN のみが掲示板（スレッド／カテゴリ）を閲覧できる。非メンバーのログイン済
     * ユーザーが閲覧を試みた場合に投げる。{@code bulletin_visibility = PUBLIC} の村では
     * ログイン済ユーザーなら誰でも閲覧可能なため本コードは発生しない。</p>
     */
    VILLAGE_BULLETIN_VIEW_FORBIDDEN("VILLAGE_081",
            "この村の掲示板は村人のみが閲覧できます", Severity.WARN),

    /**
     * VILLAGE_082: 村掲示板のモデレーション操作（ピン留め・ロック・優先度変更・他者投稿の削除等）を
     * 非モデレーター（村長 HEADMAN / 長老 ELDER でも SYSTEM_ADMIN でもないユーザー）が試みた（403）。
     *
     * <p>村掲示板グローバル方式（F17.1）の書込・モデレーション系 API で、村ロールが
     * HEADMAN / ELDER いずれにも該当しないユーザーがモデレーター専用操作を実行しようとした場合に投げる。
     * 投稿者本人による自分の投稿の更新・削除はモデレーター権限を要しないため本コードは発生しない。</p>
     */
    VILLAGE_BULLETIN_MODERATE_FORBIDDEN("VILLAGE_082",
            "この操作は村の村長または長老のみが行えます", Severity.WARN),

    // ==================================================================
    // F17.1 村長コンソール + 募集カテゴリマスタ（VILLAGE_083〜086）
    // ==================================================================
    // 設計書 docs/features/F17.1_village_headman_console_and_recruit_categories.md §8

    /** VILLAGE_083: 募集カテゴリが存在しない（404、IDOR 対策で村不一致も 404 に統一）。 */
    RECRUIT_CATEGORY_NOT_FOUND("VILLAGE_083", "募集カテゴリが見つかりません", Severity.WARN),

    /** VILLAGE_084: 同一村内でカテゴリ名が重複（409）。 */
    RECRUIT_CATEGORY_NAME_DUPLICATED("VILLAGE_084", "同じ名前の募集カテゴリが既にあります", Severity.WARN),

    /** VILLAGE_085: 1村あたりのカテゴリ数上限（20件）超過（422）。 */
    RECRUIT_CATEGORY_LIMIT_EXCEEDED("VILLAGE_085", "募集カテゴリは20件までです", Severity.WARN),

    /** VILLAGE_086: 使用中の募集カテゴリを削除しようとした（409）。 */
    RECRUIT_CATEGORY_IN_USE("VILLAGE_086", "このカテゴリを使っている募集があるため削除できません", Severity.WARN),

    // ==================================================================
    // F17.1 ②-2 村ニュースレター集計・凍結（VILLAGE_087）
    // 設計書 docs/features/F17.1_village_newsletter_content_model.md §4.2 / §11.1 AC-02
    // ==================================================================

    /**
     * VILLAGE_087: 凍結済みニュースレター号の集計値（digest_*）を更新しようとした（409）。
     *
     * <p>号の凍結ダイジェストは snapshot として確定後は不変（改ざん不可・要件①）。
     * {@code status != AGGREGATED} の号に対して再集計・再凍結を試みた場合に投げる
     * （設計書 §4.2「凍結後に集計値を更新しようとする操作は BusinessException で拒否する」）。</p>
     */
    NEWSLETTER_ISSUE_ALREADY_FROZEN("VILLAGE_087",
            "この号は既に凍結されているため集計値を変更できません", Severity.WARN),

    // ==================================================================
    // F17.1 ②-4 村ニュースレター コメント/タグ/公開一覧 API（VILLAGE_088〜092）
    // 設計書 docs/features/F17.1_village_newsletter_content_model.md §8 / §11.2 / §11.4
    // ==================================================================

    /**
     * VILLAGE_088: ニュースレター号が存在しない（404、IDOR 対策で村不一致・非公開直アクセスも 404 に統一）。
     *
     * <p>号 ID が存在しない／指定村に属さない／公開一覧で PUBLIC×PUBLISHED でない号への直アクセス時に投げる
     * （設計書 §8.2「PUBLIC 以外への直アクセスは 404 で存在秘匿」）。</p>
     */
    NEWSLETTER_ISSUE_NOT_FOUND("VILLAGE_088",
            "ニュースレターの号が見つかりません", Severity.WARN),

    /**
     * VILLAGE_089: 号の楽観ロック競合（409）。
     *
     * <p>コメント保存・タグ付け・公開範囲切替で、クライアントが送った {@code version} が号の現在値と
     * 一致しない場合に投げる（設計書 §4.4・村長と長老の同時編集を検出）。FE はリロードを促す。</p>
     */
    NEWSLETTER_ISSUE_VERSION_CONFLICT("VILLAGE_089",
            "他の管理者がこの号を更新しました。最新の内容を確認して再度お試しください", Severity.WARN),

    /** VILLAGE_090: ニュースレタータグが存在しない（404、IDOR 対策で村不一致も 404 に統一）。 */
    NEWSLETTER_TAG_NOT_FOUND("VILLAGE_090",
            "ニュースレターのタグが見つかりません", Severity.WARN),

    /** VILLAGE_091: 使用中のタグを削除しようとした（409、募集カテゴリの使用中ガードに倣う）。 */
    NEWSLETTER_TAG_IN_USE("VILLAGE_091",
            "このタグを使っている号があるため削除できません", Severity.WARN),

    /** VILLAGE_092: 同一村内でタグ名が重複（409、uk_vnt_village_name に先立つアプリ層チェック）。 */
    NEWSLETTER_TAG_DUPLICATE("VILLAGE_092",
            "同じ名前のタグが既にあります", Severity.WARN),

    /**
     * VILLAGE_093: ニュースレター<b>タグ</b>の楽観ロック競合（409・②-4 堅牢性 AC-13）。
     *
     * <p>タグ更新（{@code updateTag}）で、クライアントが送った {@code version} がタグの現在値と
     * 一致しない場合に投げる。号の版競合（{@link #NEWSLETTER_ISSUE_VERSION_CONFLICT}・VILLAGE_089）とは
     * 対象エンティティが異なる（号 vs タグ）ため、原因究明を容易にする目的で専用コードを分ける。</p>
     */
    NEWSLETTER_TAG_VERSION_CONFLICT("VILLAGE_093",
            "他の管理者がこのタグを更新しました。最新の内容を確認して再度お試しください", Severity.WARN),

    // ==================================================================
    // F17.2 Wave1 ②寄合後半戦・④年輪（VILLAGE_094〜096 / VILLAGE_101）
    // ※ VILLAGE_097〜098 は F17.2 の祭 RSVP・実況で予約済み（設計書 §16.1）。
    // ==================================================================

    /** VILLAGE_094: PLANNING 中の寄合に出欠 API を叩いた（409・出欠は CONFIRMED 限定・設計書 §4.5/AC-08）。 */
    MEETUP_NOT_CONFIRMED("VILLAGE_094",
            "この寄合はまだ日程が確定していないため、出欠を受け付けられません", Severity.WARN),

    /** VILLAGE_095: 既に割当済みの宿題 TODO への claim（409・設計書 §4.3/AC-10）。 */
    MEETUP_TODO_ALREADY_CLAIMED("VILLAGE_095",
            "この宿題は既に他の村人が引き受けています", Severity.WARN),

    /**
     * VILLAGE_096: 手挙げ者以外による宿題の complete/release（403・設計書 §4.3/AC-11・AC-12）。
     *
     * <p>complete は「手挙げ者本人＋幹事」、release は「本人のみ」に限る。権限の非対称
     * （幹事でも他人の割当は手放せない）はサービス層で分岐して判定する。</p>
     */
    MEETUP_TODO_NOT_ASSIGNEE("VILLAGE_096",
            "この宿題を操作する権限がありません", Severity.WARN),

    // ==================================================================
    // F17.2 Wave3 ⑤相性表示・⑥所属村一覧（VILLAGE_099〜100）
    // 設計書 docs/features/F17.2_village_events_activation.md §16.1
    // ==================================================================

    /**
     * VILLAGE_099: 加入前相性表示で対象村が PUBLIC でない（§8.7）。
     *
     * <p><strong>内部予約コード</strong>。UNLISTED 村への非メンバー相性アクセスは
     * 「架空の村IDへのアクセス」と区別がつかない <strong>404（{@link #VILLAGE_NOT_FOUND}）で存在秘匿</strong>するため、
     * 本コードの本文は通常返さない（存在秘匿を優先）。将来、存在を隠す必要のない内部用途が生じた場合の予約枠。</p>
     */
    AFFINITY_NOT_PUBLIC_VILLAGE("VILLAGE_099",
            "この村は相性表示の対象ではありません", Severity.WARN),

    /**
     * VILLAGE_100: 所属村一覧で返せる村が0件（403・§9.4）。
     *
     * <p>閲覧者と対象者に共通村があるか否かに関わらず、二重フィルタ（同居 ∩ 公開ON ∩ 村PUBLIC）の結果が
     * 0件になる場合は一律 403 を返す。200 空配列を返すと「この2人は同じ村に居る」という同居関係の存在を
     * 漏らす（サイドチャネル）ため、同居関係の有無ごと秘匿する。</p>
     */
    PROFILE_VILLAGES_FORBIDDEN("VILLAGE_100",
            "所属村一覧を表示する権限がありません", Severity.WARN),

    /** VILLAGE_101: 年輪（歳時記の年ごとの記録）の他人削除（403・投稿者本人＋村長/長老のみ・設計書 §6.4/AC-18b）。 */
    CALENDAR_LOG_FORBIDDEN("VILLAGE_101",
            "この記録を削除する権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
