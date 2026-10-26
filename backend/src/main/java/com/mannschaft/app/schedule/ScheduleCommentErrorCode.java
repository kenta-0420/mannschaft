package com.mannschaft.app.schedule;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.16 予定コメントスレッドのエラーコード（設計書 §4 のエラー表が正本）。
 *
 * <p><b>⚠️ HTTP ステータスはここでは決まらない。</b>
 * {@code GlobalExceptionHandler#resolveHttpStatus} は
 * {@code ERROR_CODE_STATUS_MAP} に明示登録が無いコードを {@link Severity#WARN} なら
 * <b>400</b> にフォールバックさせる。403 / 404 / 409 / 429 を返すコードは
 * <b>同 Map への登録が必須</b>で、忘れると「テストは通るのに実機で 400」という形の
 * 静かな不一致になる。本 enum の各定数と同 Map の登録は<b>対で維持する</b>こと。</p>
 *
 * <h2>存在秘匿の設計（403 ではなく 404）</h2>
 * <p>{@link #SCHEDULE_NOT_VISIBLE}（{@code SCHEDULE_COMMENT_002}）は「閲覧権限なし」でも
 * <b>404</b> を返す。403 だと「その予定は存在する」という事実自体が漏れ、
 * 予定 ID の総当たりで他チームの予定の存在を列挙できてしまうためである（§2.2 / §4.5）。
 * 個人予定（スコープ外）も同じ理由で 404 に寄せる。</p>
 *
 * <h2>409 を 1 コードに束ねる理由</h2>
 * <p>{@link #NOT_WRITABLE}（{@code SCHEDULE_COMMENT_005}）は「スレッドが閉じている」と
 * 「予定が中止」の<b>両方</b>を表す。設計書 §5.2 が
 * {@code writable(schedule) := comments_enabled AND status != CANCELLED} という
 * <b>単一述語</b>に一本化しており、理由で呼び分けると片方だけ実装される温床になる。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleCommentErrorCode implements ErrorCode {

    /** 400 — {@code body} が空／トリム後に空／2000 文字超（§4.2）。 */
    INVALID_BODY("SCHEDULE_COMMENT_001", "コメント本文は1〜2000文字で入力してください", Severity.WARN),

    /** 404 — 予定が不存在／個人予定／<b>閲覧権限なし（存在秘匿）</b>（§2.2 / §4.5）。 */
    SCHEDULE_NOT_VISIBLE("SCHEDULE_COMMENT_002", "予定が見つかりません", Severity.WARN),

    /** 404 — コメントが当該予定に属さない／不存在／削除済み（IDOR 経路の遮断・§4.1）。 */
    COMMENT_NOT_FOUND("SCHEDULE_COMMENT_003", "コメントが見つかりません", Severity.WARN),

    /** 403 — 認証済みだが投稿要件（{@code postableRole}）を満たさない（§2.1）。 */
    POST_NOT_ALLOWED("SCHEDULE_COMMENT_004", "この予定にコメントする権限がありません", Severity.WARN),

    /** 409 — {@code writable()} が false＝スレッドが閉じている<b>または</b>予定が中止（§5.2）。 */
    NOT_WRITABLE("SCHEDULE_COMMENT_005", "この予定のコメントは締め切られています", Severity.WARN),

    /** 400 — {@code parentId} が別予定のコメント／{@code depth} 不整合（§3.3）。 */
    INVALID_HIERARCHY("SCHEDULE_COMMENT_006", "返信先の指定が不正です", Severity.WARN),

    /** 400 — {@code depth = 1} のコメントへ返信しようとした（§3.3.1・自動付け替えはしない）。 */
    REPLY_DEPTH_EXCEEDED("SCHEDULE_COMMENT_007", "返信にさらに返信することはできません", Severity.WARN),

    /** 400 — {@code mentionedUserIds} が 20 件超（§4.2）。 */
    TOO_MANY_MENTIONS("SCHEDULE_COMMENT_008", "メンションは20件までです", Severity.WARN),

    /** 403 — 自分のコメントではない。<b>ADMIN でも他者コメントの本文編集は不可</b>（改竄防止・§4.4）。 */
    EDIT_NOT_OWNER("SCHEDULE_COMMENT_009", "自分のコメントのみ編集できます", Severity.WARN),

    /** 403 — 自分のコメントでなく、{@code DELETE_OTHERS_CONTENT} も ADMIN 権限も無い（§2.1.2）。 */
    DELETE_NOT_ALLOWED("SCHEDULE_COMMENT_010", "このコメントを削除する権限がありません", Severity.WARN),

    /** 403 — スレッド開閉は SYSTEM_ADMIN／スコープ ADMIN／予定作成者の 3 者のみ（§2.1.1）。 */
    THREAD_SETTINGS_NOT_ALLOWED("SCHEDULE_COMMENT_011", "コメントの開閉を変更する権限がありません", Severity.WARN),

    /** 429 — レート制限（§10.2）。 */
    RATE_LIMITED("SCHEDULE_COMMENT_012", "コメントの投稿が多すぎます。しばらく待ってから再度お試しください", Severity.WARN),
    ;

    private final String code;
    private final String message;
    private final Severity severity;
}
