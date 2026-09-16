package com.mannschaft.app.jobmatching.exception;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F13.1 求人マッチング機能のエラーコード定義。
 *
 * <p>Phase 13.1.1 MVP の範囲で発生し得るエラーを網羅する。
 * 各コードの HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} にて
 * 個別にマッピングされる（404/409/403 等、Severity デフォルトの 400 と異なるもの）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum JobmatchingErrorCode implements ErrorCode {

    /** 求人が見つからない（論理削除済み・ID不一致を含む） */
    JOB_NOT_FOUND("JOB_NOT_FOUND", "求人が見つかりません", Severity.WARN),

    /** 求人が公開状態でない（DRAFT/CLOSED/CANCELLED 等） */
    JOB_NOT_OPEN("JOB_NOT_OPEN", "求人が公開状態ではありません", Severity.WARN),

    /** 定員充足により応募不可 */
    JOB_CAPACITY_FULL("JOB_CAPACITY_FULL", "求人の定員に達しています", Severity.WARN),

    /** 応募締切を過ぎている */
    JOB_DEADLINE_PASSED("JOB_DEADLINE_PASSED", "応募締切を過ぎています", Severity.WARN),

    /** 既に同じ求人へ応募済み */
    JOB_ALREADY_APPLIED("JOB_ALREADY_APPLIED", "既にこの求人へ応募済みです", Severity.WARN),

    /** 応募レコードが見つからない */
    JOB_APPLICATION_NOT_FOUND("JOB_APPLICATION_NOT_FOUND", "応募が見つかりません", Severity.WARN),

    /** 自分自身が投稿した求人への応募は不可 */
    JOB_CANNOT_APPLY_SELF("JOB_CANNOT_APPLY_SELF", "自分が投稿した求人へは応募できません", Severity.WARN),

    /** 応募が既に処理済み（採用/不採用/取り下げ） */
    JOB_APPLICATION_NOT_PENDING("JOB_APPLICATION_NOT_PENDING", "応募は既に処理済みです", Severity.WARN),

    /** 契約レコードが見つからない */
    JOB_CONTRACT_NOT_FOUND("JOB_CONTRACT_NOT_FOUND", "契約が見つかりません", Severity.WARN),

    /** 許可されていない状態遷移 */
    JOB_INVALID_STATE_TRANSITION("JOB_INVALID_STATE_TRANSITION", "この状態遷移は許可されていません", Severity.WARN),

    /**
     * 操作権限がない（404）。
     *
     * <p><b>これは「意味が割れている」のではなく意図的な集約である。分割してはならない。</b>
     * 本コードは (1) 他人が当事者である求人・応募・契約 ID への越境アクセス と (2) 当事者ではあるが当該操作を行えない者による権限拒否 の両方に使われる。この2つを別コード・別ステータスに分けると、応答の差から
     * 「そのIDのリソースは実在する」ことを外部から判定できる存在オラクルになる。</p>
     *
     * <p><b>ステータスは404固定。</b>不在（{@link #JOB_CONTRACT_NOT_FOUND}）と同一の404に畳むことで秘匿を達成する。
     * このコードベースには PARKING_020 を起点とする「越境は存在秘匿で404」の流儀が確立しており
     * （equipment/membership/todo/corkboard/pointcard/skill で実装済み）、それに揃えた。
     * かつては 403 を返しており、不在（404）と越境（403）でステータスが割れて存在オラクルになっていた。
     * 「403に戻すべきでは」と迷った場合は、この理由を思い出すこと。
     * （{@code GlobalExceptionHandlerTest.ExistenceOracleParity} が
     * 「不在と越境の応答が一致すること」を契約として固定している）。</p>
     */
    JOB_PERMISSION_DENIED("JOB_PERMISSION_DENIED", "この操作を行う権限がありません", Severity.WARN),

    /**
     * 求人の新規作成権限がない（403）。
     *
     * <p>これは {@link #JOB_PERMISSION_DENIED} とは別物である。求人 ID を一切引かない汎用の権限拒否であり、
     * 秘匿すべきリソース ID が存在しないため、ID 越境の 404 化（存在秘匿）の対象にはならない。
     * したがってステータスは 403 のまま据え置く。</p>
     */
    JOB_CREATE_PERMISSION_DENIED("JOB_CREATE_PERMISSION_DENIED", "求人を作成する権限がありません", Severity.WARN),

    /** 指定された公開範囲は MVP 未対応 */
    JOB_VIS_NOT_SUPPORTED("JOB_VIS_NOT_SUPPORTED", "指定された公開範囲は現在サポートされていません", Severity.WARN),

    /** 報酬額が許容範囲外 */
    JOB_REWARD_OUT_OF_RANGE("JOB_REWARD_OUT_OF_RANGE", "報酬額が許容範囲外です", Severity.WARN),

    /** 差し戻し回数の上限を超過 */
    JOB_REJECTION_LIMIT_EXCEEDED("JOB_REJECTION_LIMIT_EXCEEDED", "差し戻し回数の上限を超過しました", Severity.WARN),

    // ===== F13.1 Phase 13.1.2: QR チェックイン／アウト =====

    /** QR トークン失効（TTL 超過） / 400 Bad Request */
    JOB_QR_TOKEN_EXPIRED("JOB_QR_TOKEN_EXPIRED", "QR コードの有効期限が切れています", Severity.WARN),

    /** QR トークン再利用（used_at が既に記録されている） / 400 Bad Request */
    JOB_QR_TOKEN_REUSED("JOB_QR_TOKEN_REUSED", "この QR コードは既に使用されています", Severity.WARN),

    /** QR トークン署名検証失敗（改ざん疑い） / 401 Unauthorized */
    JOB_QR_TOKEN_INVALID_SIGNATURE("JOB_QR_TOKEN_INVALID_SIGNATURE",
            "QR コードの署名検証に失敗しました", Severity.WARN),

    /** 採用確定 Worker 以外のスキャン（ペイロードの worker_user_id 不一致） / 403 Forbidden */
    JOB_QR_TOKEN_WRONG_WORKER("JOB_QR_TOKEN_WRONG_WORKER",
            "この QR コードはあなたが採用された求人のものではありません", Severity.WARN),

    /** 同一契約・同一種別のチェックイン／アウトが既に存在 / 400 Bad Request */
    JOB_CHECK_IN_ALREADY_EXISTS("JOB_CHECK_IN_ALREADY_EXISTS",
            "既にチェックイン／アウト済みです", Severity.WARN),

    /** チェックイン未完のままチェックアウトを試みた / 409 Conflict */
    JOB_CHECK_OUT_BEFORE_CHECK_IN("JOB_CHECK_OUT_BEFORE_CHECK_IN",
            "チェックインが完了していないためチェックアウトできません", Severity.WARN),

    /** 同時刻に他契約でチェックイン中（掛け持ち禁止） / 403 Forbidden */
    JOB_CHECK_IN_CONCURRENT_CONFLICT("JOB_CHECK_IN_CONCURRENT_CONFLICT",
            "同じ時間帯に別の契約でチェックイン中のため、この契約へのチェックインはできません", Severity.WARN),

    /** 手動入力フォールバック用の短コードが見つからない（失効・未発行） / 400 Bad Request */
    JOB_QR_SHORT_CODE_NOT_FOUND("JOB_QR_SHORT_CODE_NOT_FOUND",
            "入力された短コードは無効または失効しています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
