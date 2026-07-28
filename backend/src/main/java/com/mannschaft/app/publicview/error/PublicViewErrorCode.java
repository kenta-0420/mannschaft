package com.mannschaft.app.publicview.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F19.1 公開ページ機能のエラーコード定義。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.5</p>
 *
 * <p>IDOR / エニュメレーション対策の観点から、PUBLIC でないチーム / 組織 / 投稿に
 * 対するアクセスは全て 404 で隠蔽する。本 Enum は主に内部監査・ログ用識別子として用い、
 * クライアントには {@link com.mannschaft.app.common.GlobalExceptionHandler} の
 * {@code ERROR_CODE_STATUS_MAP} 経由で 404 / 429 へ正規化される。</p>
 */
@Getter
@RequiredArgsConstructor
public enum PublicViewErrorCode implements ErrorCode {

    /** 指定されたチーム / 組織は存在しないか公開されていません (404 へ正規化)。 */
    PUBLIC_001("PUBLIC_001",
            "指定されたチーム / 組織は存在しないか公開されていません",
            Severity.WARN),

    /** アクセス頻度が高すぎます (429 へ正規化、PR-3 の AuditEventType と整合)。 */
    PUBLIC_002("PUBLIC_002",
            "アクセス頻度が高すぎます。しばらく時間を空けて再度お試しください",
            Severity.WARN),

    /** 指定された投稿は存在しないか公開されていません (404 へ正規化)。 */
    PUBLIC_003("PUBLIC_003",
            "指定された投稿は存在しないか公開されていません",
            Severity.WARN),

    /** 切替対象のチーム / 組織が見つかりません (404)。 */
    NAME_DISCLOSURE_NOT_FOUND("PUBLIC_004",
            "指定されたチーム / 組織が見つかりません",
            Severity.WARN),

    /** confirmed=false のまま切替を要求されました (400)。 */
    NAME_DISCLOSURE_CONFIRM_REQUIRED("PUBLIC_005",
            "投稿者識別モードの切替確認が必要です",
            Severity.WARN),

    /** 切替権限がありません (403)。 */
    NAME_DISCLOSURE_FORBIDDEN("PUBLIC_006",
            "投稿者識別モードの切替権限がありません",
            Severity.WARN),

    /**
     * F19.1 Phase 6: 指定されたユーザーのプロフィールは公開されていません (404 へ正規化)。
     *
     * <p>IDOR / エニュメレーション対策の観点から、存在しないユーザー・非公開ユーザー・
     * 削除済みユーザーを区別せず一律 404 で返す。</p>
     */
    PUBLIC_007("PUBLIC_007",
            "ユーザープロフィールが公開されていません",
            Severity.WARN),

    /**
     * F19.1 Phase 6-B: コメント対象の投稿が存在しないか公開されていません (404 へ正規化)。
     *
     * <p>IDOR 対策のため、存在しない投稿・非公開投稿・削除済み投稿を区別しない。</p>
     */
    PUBLIC_008("PUBLIC_008",
            "コメント対象の投稿が存在しないか公開されていません",
            Severity.WARN),

    /**
     * F19.1 Phase 6-B: コメントが見つかりません (404 へ正規化)。
     */
    PUBLIC_009("PUBLIC_009",
            "コメントが見つかりません",
            Severity.WARN),

    /**
     * F19.1 Phase 6-B: このコメントを削除する権限がありません (403 へ正規化)。
     */
    PUBLIC_010("PUBLIC_010",
            "このコメントを削除する権限がありません",
            Severity.WARN),

    /**
     * F19.1 Phase 7: この投稿の public_visible フラグを変更する権限がありません (403 へ正規化)。
     * 投稿者本人以外が操作しようとした場合。
     */
    PUBLIC_011("PUBLIC_011",
            "この投稿の公開設定を変更する権限がありません",
            Severity.WARN),

    /**
     * F19.1 Phase 7: 公開設定を変更する権限がありません (403 へ正規化)。
     * チーム/組織の公開設定（タイムライン/イベント）を権限なしで変更しようとした場合。
     */
    PUBLIC_012("PUBLIC_012",
            "公開設定を変更する権限がありません",
            Severity.WARN),

    /**
     * F06.4 公開活動記録: 活動記録が存在しないか公開されていません (404 へ正規化)。
     *
     * <p>匿名公開経路（{@code /api/v1/public} 配下の activities 系 5 EP）の <b>唯一の失敗コード</b>。
     * 「存在しない」「visibility が PUBLIC でない」「status が DRAFT」「論理削除済み」
     * 「親スコープ（チーム / 組織）が非公開・凍結・停止」「パス変数と実スコープの不一致（詐称）」の
     * <b>すべてを本コード 1 つに倒す</b>ことで、ステータスコードもレスポンスボディも
     * 区別できないようにし、ID 列挙オラクルを封じる（契約テスト AC-18）。</p>
     *
     * <p>個別の理由を返り値やメッセージで分岐させてはならない。分岐した瞬間に
     * 「どの ID が実在するか」を攻撃者に教えることになる。</p>
     */
    PUBLIC_013("PUBLIC_013",
            "指定された活動記録は存在しないか公開されていません",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
