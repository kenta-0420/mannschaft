package com.mannschaft.app.reservation;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.4 予約管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

    /** 予約ラインが見つからない */
    LINE_NOT_FOUND("RESERVATION_001", "予約ラインが見つかりません", Severity.WARN),

    /** 予約スロットが見つからない */
    SLOT_NOT_FOUND("RESERVATION_002", "予約スロットが見つかりません", Severity.WARN),

    /** 予約が見つからない */
    RESERVATION_NOT_FOUND("RESERVATION_003", "予約が見つかりません", Severity.WARN),

    /** スロットが満席 */
    SLOT_FULL("RESERVATION_004", "このスロットは満席です", Severity.WARN),

    /** スロットがクローズ済み */
    SLOT_CLOSED("RESERVATION_005", "このスロットは受付終了しています", Severity.WARN),

    /** 予約ステータス不正 */
    INVALID_RESERVATION_STATUS("RESERVATION_006", "この操作は現在の予約ステータスでは実行できません", Severity.WARN),

    /** 開始時刻と終了時刻の整合性エラー（入力不正なので 400） */
    INVALID_TIME_RANGE("RESERVATION_007", "開始時刻は終了時刻より前である必要があります", Severity.WARN),

    /** 営業時間外 */
    OUTSIDE_BUSINESS_HOURS("RESERVATION_008", "営業時間外の時刻が指定されています", Severity.WARN),

    /** ブロック時間帯 */
    BLOCKED_TIME_CONFLICT("RESERVATION_009", "ブロックされた時間帯と重複しています", Severity.WARN),

    /** 営業時間が見つからない */
    BUSINESS_HOURS_NOT_FOUND("RESERVATION_010", "営業時間設定が見つかりません", Severity.WARN),

    /** ブロック時間が見つからない */
    BLOCKED_TIME_NOT_FOUND("RESERVATION_011", "ブロック時間が見つかりません", Severity.WARN),

    /** リマインダーが見つからない */
    REMINDER_NOT_FOUND("RESERVATION_012", "リマインダーが見つかりません", Severity.WARN),

    /** 予約重複 */
    DUPLICATE_RESERVATION("RESERVATION_013", "同じスロットに既に予約が存在します", Severity.WARN),

    /** 過去日付への予約 */
    PAST_DATE_RESERVATION("RESERVATION_014", "過去の日付には予約できません", Severity.WARN),

    /** リマインダー上限超過 */
    MAX_REMINDERS_EXCEEDED("RESERVATION_015", "リマインダーは最大3件です", Severity.ERROR),

    /** 臨時休業が見つからない */
    CLOSURE_NOT_FOUND("RESERVATION_016", "臨時休業が見つかりません", Severity.WARN),

    /** 臨時休業確認レコードが見つからない */
    CLOSURE_CONFIRMATION_NOT_FOUND("RESERVATION_017", "臨時休業確認レコードが見つかりません", Severity.WARN),

    /** 臨時休業の日付範囲が不正（入力不正なので 400） */
    INVALID_CLOSURE_DATE_RANGE("RESERVATION_018", "終了日は開始日以降である必要があります", Severity.WARN),

    /** 臨時休業の時刻範囲が不正（入力不正なので 400） */
    INVALID_CLOSURE_TIME_RANGE("RESERVATION_019", "時刻範囲が不正です。開始・終了は両方指定し、整時（HH:00）かつ開始 < 終了である必要があります", Severity.WARN),

    /** 予約入り枠の削除拒否（active な予約が紐づくスロットは削除不可・409） */
    SLOT_HAS_ACTIVE_RESERVATIONS("RESERVATION_020", "このスロットには有効な予約が存在するため削除できません", Severity.WARN),

    /**
     * 予約認可ゲート: チーム所属者でない者が一般公開OFFのチームに予約しようとした。
     *
     * <p>既定（allow_public_reservation=false）はチーム所属（SUPPORTER 以上＝memberships 存在）を要求する。
     * 裏設定で公開（true）にした場合はログイン済みであれば誰でも予約可（匿名は認証層で 401）。
     * Severity.WARN だが {@code GlobalExceptionHandler} の個別マッピングで HTTP 403 に上書きする。</p>
     */
    RESERVATION_PERMISSION_DENIED("RESERVATION_021", "このチームに予約する権限がありません", Severity.WARN),

    /**
     * 枠の時刻が 30 分グリッドに乗っていない、または枠長が 30 分未満（入力不正なので 400）。
     *
     * <p>F03.4 §3 「{@code start_time}/{@code end_time} の分は {@code 00} または {@code 30} のみ。最小枠 30 分」を
     * Service 層（{@code ReservationSlotService.validateTimeRange}）で担保する（段階拡張バックログ ②）。</p>
     */
    INVALID_SLOT_GRANULARITY("RESERVATION_022", "予約枠は30分単位で、最小30分以上である必要があります", Severity.WARN),

    /**
     * 過去日付の枠作成（入力不正なので 400）。
     *
     * <p>F03.4 §3 「{@code slot_date} は当日以降のみ作成可能。過去日は 400」を Service 層で担保する
     * （段階拡張バックログ ③）。予約（reservation）用の {@link #PAST_DATE_RESERVATION} とは
     * 文脈が異なる（こちらは ADMIN による枠定義）ため別コードを割り当てる。判定は注入 {@code Clock} 基準。</p>
     */
    PAST_DATE_SLOT("RESERVATION_023", "過去の日付には予約枠を作成できません", Severity.WARN),

    /**
     * 予約ライン数の上限（20 本）超過（入力不正なので 400）。
     *
     * <p>F03.4 §1/§2 の上限を Service 層（{@code ReservationLineService.createLine}）で担保する
     * （段階拡張バックログ ④）。F03.4.2 §3.4 で 5→20 へ拡張（コード再利用・番号変更なし）。</p>
     */
    LINE_LIMIT_EXCEEDED("RESERVATION_024", "予約ラインはチームあたり最大20本までです", Severity.WARN),

    /**
     * 予約ラインの表示順（display_order）がチーム内許可範囲（1〜20）外（入力不正なので 400）。
     *
     * <p>F03.4 §2 「{@code display_order} は Service 層で保証」を担保する（段階拡張バックログ ④ の付随検証）。
     * F03.4.2 §3.4 で範囲を 1〜5 → 1〜20 へ拡張。</p>
     */
    INVALID_DISPLAY_ORDER("RESERVATION_025", "表示順は1〜20の範囲で指定してください", Severity.WARN),

    /**
     * キャンセル締切超過（会員キャンセル拒否・入力不正なので 400）。
     *
     * <p>F03.4 §3 「{@code reservation_policies.cancel_deadline_hours}（既定 24）を実適用し、
     * 枠開始時刻の {@code deadline} 時間前を過ぎた会員（USER）キャンセルは拒否する」を
     * Service 層（{@code ReservationService.cancelByUser}）で担保する（段階拡張バックログ ⑤）。
     * 判定は注入 {@code Clock} 基準。管理者（ADMIN）キャンセルは締切の対象外（常時キャンセル可）。
     * Severity.WARN のため {@code GlobalExceptionHandler} の既定マッピングで 400 になる（個別 map 不要）。</p>
     */
    CANCEL_DEADLINE_PASSED("RESERVATION_026", "キャンセル締切を過ぎているためキャンセルできません", Severity.WARN),

    /**
     * 予約不可枠（機能B）の登録/更新時、対象枠と時間帯 overlap する active 予約
     * （{@code PENDING} / {@code CONFIRMED}）が既に存在する（リソース競合・409）。
     *
     * <p>F03.4 §3.B/§5.B「予約不可枠 作成/更新の 409 ガード」を担保する。
     * 既存予約は強制キャンセルせず、管理者が impact API（{@code .../blocked-times/impact}）で
     * 確認 → 振替/キャンセルしてから登録する運用の最終防御。
     * Severity.WARN だが {@code GlobalExceptionHandler} の個別マッピングで HTTP 409 に上書きする。</p>
     */
    UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS("RESERVATION_027",
            "この予約不可枠と重複する有効な予約が存在します。先に振替またはキャンセルしてください", Severity.WARN),

    // ===== 機能D: 予約通知メール宛先（フリーミアム件数ゲート）=====

    /**
     * 予約通知メール宛先が上限（10 件）に到達（入力上限超過なので 400）。
     *
     * <p>F03.4 §4.D/§5.D「{@code count >= MAX_RECIPIENT_LIMIT(10)}」を Service 層
     * （{@code ReservationNotificationRecipientService.addRecipient}）で担保する。
     * 有料でも 10 件超は不可。Severity.WARN のため {@code GlobalExceptionHandler} の
     * 既定マッピングで 400 になる（個別 map 不要）。</p>
     */
    NOTIFY_RECIPIENT_LIMIT_EXCEEDED("RESERVATION_028", "予約通知メール宛先はチームあたり最大10件までです", Severity.WARN),

    /**
     * 無料プランで 4 件目以降の宛先を追加（有料プラン必須なので 402 Payment Required）。
     *
     * <p>F03.4 §4.D/§5.D「{@code count >= FREE_RECIPIENT_LIMIT(3)} かつ {@code !hasPaidPlan}」を
     * {@code TeamPlanService.hasPaidPlan} で判定して担保する。HTTP は 402（Payment Required＝
     * 有料課金で解放される意味論）。{@code GlobalExceptionHandler} の個別マッピングで 402 に上書きする。</p>
     */
    NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED("RESERVATION_029", "無料プランでは予約通知メール宛先は3件までです。4件目以降は有料プランが必要です", Severity.WARN),

    /**
     * 同一チームで email 重複（リソース競合なので 409 Conflict）。
     *
     * <p>F03.4 §4.D「同一チームで {@code email} 重複」を Service 層で事前に 409 として弾く。
     * DB {@code UNIQUE(team_id, email)} が最終防御。{@code GlobalExceptionHandler} の
     * 個別マッピングで 409 に上書きする。</p>
     */
    NOTIFY_RECIPIENT_DUPLICATE("RESERVATION_030", "この宛先メールアドレスは既に登録されています", Severity.WARN),

    /**
     * 予約通知メール宛先が見つからない（PATCH/DELETE 対象不在・404）。
     *
     * <p>{@code findByIdAndTeamId} で解決できない場合に throw する（他チームの宛先を掴んだ場合も
     * IDOR 対策として同一の 404 で隠蔽する）。{@code GlobalExceptionHandler} の個別マッピングで 404。</p>
     */
    NOTIFY_RECIPIENT_NOT_FOUND("RESERVATION_031", "予約通知メール宛先が見つかりません", Severity.WARN),

    // ===== F03.4.1 機能E: 予約メニュー（v2 第一弾）=====

    /**
     * メニュー不存在（PATCH/DELETE 対象不在・404）。
     *
     * <p>F03.4.1 §4/§6: {@code findByIdAndTeamId} で解決できない場合に throw する。
     * 他チームのメニュー ID を掴んだ場合も IDOR 対策として同一の 404 で隠蔽する。
     * {@code GlobalExceptionHandler} の個別マッピングで 404。</p>
     */
    MENU_NOT_FOUND("RESERVATION_032", "メニューが見つかりません", Severity.WARN),

    /**
     * メニュー上限（1 チームあたり 20 件）超過（入力上限超過なので 400）。
     *
     * <p>F03.4.1 §3: 論理削除済みは数えない（有効・無効は問わず数える）。
     * Service 層（{@code ReservationMenuService.createMenu}）で担保する。</p>
     */
    MENU_LIMIT_EXCEEDED("RESERVATION_033", "メニューはチームあたり最大20件までです", Severity.WARN),

    /**
     * 所要時間が 30 の倍数でない / 30〜480 範囲外（入力不正なので 400）。
     *
     * <p>F03.4.1 §3: {@code duration_minutes} は 30 の倍数・30〜480。Service 層が一次検証、
     * DB の CHECK 制約（MySQL 8.0.16+ 実 enforce）が最終防御。</p>
     */
    INVALID_MENU_DURATION("RESERVATION_034", "所要時間は30分単位（30〜480分）で指定してください", Severity.WARN),

    /**
     * <b>メニュー定義時（POST/PATCH）</b>の {@code lineIds} 不正（入力不正なので 400）。
     *
     * <p>F03.4.1 §4/§9: 不正 ID / 他チームのライン / 削除済みラインを含む場合。
     * 他チームのライン ID も同コード（存在秘匿）。予約時の「提供不可ラインでの確保」は
     * 別コード RESERVATION_043（F03.4.3 で採番）— 意味衝突の回避。</p>
     */
    MENU_LINE_IDS_INVALID("RESERVATION_035", "選択した予約対象が無効です", Severity.WARN),

    // ===== F03.4.2 機能F: 枠ライン軸＋週間テンプレート =====

    /**
     * 週間テンプレートが見つからない（PATCH/DELETE 対象不在・404）。
     *
     * <p>F03.4.2 §4/§6: {@code findByIdAndTeamId} で解決できない場合に throw する。
     * 他チームのテンプレートを掴んだ場合も IDOR 対策として同一の 404 で秘匿する。
     * {@code GlobalExceptionHandler} の個別マッピングで 404。</p>
     */
    TEMPLATE_NOT_FOUND("RESERVATION_036", "週間テンプレートが見つかりません", Severity.WARN),

    /**
     * 週間テンプレートの行数上限（1チーム 500 行）超過（入力上限超過なので 400）。
     *
     * <p>F03.4.2 §3.2: 20ライン × 7曜日 × 帯3本/日 = 420 行 &lt; 500 の試算で、
     * フル運用チームを包含しつつ生成暴走を防ぐ。Severity.WARN のため既定マッピングで 400。</p>
     */
    TEMPLATE_LIMIT_EXCEEDED("RESERVATION_037", "週間テンプレートはチームあたり最大500行までです", Severity.WARN),

    /**
     * 選択枠とラインの不一致（入力不正なので 400）。
     *
     * <p>F03.4.2 §5.6: ライン軸枠（{@code slot.line_id} 非 NULL）の単枠予約で
     * {@code request.lineId != slot.lineId} の場合に拒否する（枠の帰属と矛盾する予約を防ぐ）。
     * F03.4.3（予約グループ）の「選択枠が非連続/同一日でない/ライン不一致」も<b>同一コードを共用</b>する
     * （同一意味論のため単枠専用の新規採番はしない — F03.4.3 §9 の定数名案は
     * {@code GROUP_SLOTS_NOT_CONSECUTIVE}。グループ実装時に用途拡張する）。</p>
     */
    SLOT_LINE_MISMATCH("RESERVATION_038", "選択した枠とラインが一致しません", Severity.WARN),

    // ===== F03.4.3 機能G: 予約グループ（複数枠・連続枠予約 / v2 第二弾）=====

    /**
     * グループ内のいずれかの枠が満席/CLOSED で確保失敗（リソース競合・409）。
     *
     * <p>F03.4.3 §5.2: 確保は {@code incrementBookedCountIfAvailable} のリポジトリ直呼び×N
     * （slotId 昇順・確保 UPDATE → INSERT の順）。0 行更新 = 確保失敗で本コードを throw し、
     * {@code @Transactional} が先行確保分も含めて全ロールバックする（部分成功禁止）。
     * SLOT_FULL(004・400) ではなく本コード（409）を使い「グループの一部枠が確保できなかった」ことを
     * FE が区別する（§5.2 の 4）。稀な InnoDB デッドロック
     * （{@code PessimisticLockingFailureException}）も同じ「選び直し」契約として本コードへマップする（§5.2）。
     * {@code GlobalExceptionHandler} の個別マッピングで HTTP 409。</p>
     */
    GROUP_SLOT_UNAVAILABLE("RESERVATION_039",
            "選択した枠のいずれかが確保できませんでした。空き状況を更新して選び直してください", Severity.WARN),

    /**
     * 予約グループ不存在・権限なし（存在秘匿・404）。
     *
     * <p>F03.4.3 §4/§6: 存在しない / 他チーム / 他人（非 ADMIN）の groupId はすべて本コードで
     * 404 に統一する（UUID 列挙攻撃に対して存在自体を隠す IDOR 秘匿）。
     * {@code GlobalExceptionHandler} の個別マッピングで HTTP 404。</p>
     */
    GROUP_NOT_FOUND("RESERVATION_040", "予約グループが見つかりません", Severity.WARN),

    /**
     * グループ枠数上限（16 枠）超過（入力不正なので 400）。
     *
     * <p>F03.4.3 §5.2-e: {@code 1 <= slotIds.length <= 16}。17 枠以上の指定は買い占め防止の観点から拒否する。
     * Severity.WARN のため既定マッピングで 400（個別 map 不要）。</p>
     */
    GROUP_SIZE_EXCEEDED("RESERVATION_041", "予約グループは最大16枠までです", Severity.WARN),

    /**
     * グループ所属行への単票操作の拒否（入力不正なので 400）。
     *
     * <p>F03.4.3 §4: グループ所属行（{@code group_id IS NOT NULL}）への単票状態遷移 API
     * （cancel/confirm/complete/no-show/reschedule）は部分遷移によるグループ状態の分裂・
     * booked_count 不整合を構造的に防ぐため全行で拒否する。非代表行へのメモ更新
     * （admin-note）も一覧に浮上せず事実上消失するため拒否する（代表行のみ許可）。
     * Severity.WARN のため既定マッピングで 400。</p>
     */
    GROUP_ROW_DIRECT_OPERATION_NOT_ALLOWED("RESERVATION_042",
            "この予約はグループの一部です。グループ単位で操作してください", Severity.WARN),

    /**
     * <b>予約時</b>に選択メニューが対象ラインで提供されていない（入力不正なので 400）。
     *
     * <p>F03.4.3 §5.2-f: {@code reservation_menu_lines} が 1 件以上列挙されているメニューで、
     * 列挙に {@code request.lineId} が含まれない場合（0 件 = 全ライン可）。
     * F03.4.1 の 035（メニュー<b>定義時</b>の lineIds 不正）とは発生文脈・利用者・FE 導線が
     * 異なるため別コードに分離する（意味衝突回避・精査第1パス指摘12）。
     * Severity.WARN のため既定マッピングで 400。</p>
     */
    GROUP_MENU_LINE_NOT_OFFERED("RESERVATION_043",
            "選択したメニューはこの予約対象では提供されていません", Severity.WARN),

    /**
     * 一括生成（generate）のレートリミット超過（429 Too Many Requests）。
     *
     * <p>F03.4.2 §6「generate は 1 チーム 1 分間に 2 回まで」の資源保護。
     * §9 の採番表には現れないが §6 が要求する挙動のための採番（039〜043 は F03.4.3/4 が
     * 採番予定のため 044 を使用）。{@code GlobalExceptionHandler} の個別マッピングで 429。</p>
     */
    TEMPLATE_GENERATE_RATE_LIMITED("RESERVATION_044",
            "枠の一括作成が短時間に繰り返されています。しばらく待ってから再実行してください", Severity.WARN),

    /**
     * ライン削除ガード: 当該ラインに active 予約（PENDING / CONFIRMED）が存在する（409）。
     *
     * <p>F03.4.2 §5.5（精査2パス A1 再設計）: ライン削除の<b>唯一の</b> 409 事由。
     * 「予約のない未来のライン軸枠が存在する」ことは 409 事由にしない（旧設計の循環デッドロックの根を除去）。
     * §9 の採番表には現れないが §5.5 が要求する挙動のための採番。
     * {@code GlobalExceptionHandler} の個別マッピングで 409。</p>
     */
    LINE_HAS_ACTIVE_RESERVATIONS("RESERVATION_045",
            "このラインには有効な予約があります。先に振替またはキャンセルしてください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
