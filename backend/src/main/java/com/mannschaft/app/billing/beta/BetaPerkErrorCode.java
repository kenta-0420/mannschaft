package com.mannschaft.app.billing.beta;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F20.3 ベータ特典のエラーコード（設計書 02 §8）。
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に明示登録する
 * （登録漏れは Severity 既定の 400/500 にフォールバックする前科 #1279 があるため）。
 * 400=入力不正／403=禁止／404=不在（IDOR 秘匿含む）／409=状態競合／422=業務ルール違反。</p>
 *
 * <p><b>採番注記</b>: {@code BETA_PERK_} プレフィックスは新設。マージ時に
 * {@code git grep "BETA_PERK_0"} で並行 PR との衝突を再確認すること（設計書 02 §8）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum BetaPerkErrorCode implements ErrorCode {

    /** grant が存在しない（他スコープの ID は IDOR 秘匿で本コード）→ 404。 */
    GRANT_NOT_FOUND("BETA_PERK_001", "指定されたベータ特典が見つかりません", Severity.WARN),

    /** 同一 scope × beta_phase に付与済み（uk_bg_scope_phase）→ 409。 */
    GRANT_ALREADY_EXISTS("BETA_PERK_002", "このスコープには当該フェーズの特典が既に付与されています", Severity.WARN),

    /** 付与条件未達（details に実測値/閾値を含める）→ 422。 */
    ACTIVITY_CRITERIA_NOT_MET("BETA_PERK_003", "特典の付与条件を満たしていません", Severity.WARN),

    /** beta_phase が 1〜4 以外 → 400。 */
    BETA_PHASE_INVALID("BETA_PERK_004", "ベータ段階の指定が不正です", Severity.WARN),

    /** 取消済み grant への操作（revoke/extend/flag）→ 409。 */
    GRANT_ALREADY_REVOKED("BETA_PERK_005", "この特典は既に取消済みです", Severity.WARN),

    /** review_flag=false への resolve-review → 409。 */
    REVIEW_NOT_FLAGGED("BETA_PERK_006", "この特典は審査待ちではありません", Severity.WARN),

    /** grant_kind × scope_kind の不整合（AC-16）→ 422。 */
    GRANT_SCOPE_MISMATCH("BETA_PERK_007", "付与種別とスコープ種別の組み合わせが不正です", Severity.WARN),

    /** INDIVIDUAL（無期限）への延長操作 → 422。 */
    EXTEND_NOT_APPLICABLE("BETA_PERK_008", "個人特典は無期限のため延長できません", Severity.WARN),

    /** 条件マスタの全指標 NULL 等（無条件付与の防止）→ 400。 */
    CRITERIA_VALIDATION_FAILED("BETA_PERK_009", "付与条件マスタの整合性違反です", Severity.WARN),

    /** 対象フェーズ × 種別の criteria が未定義/enabled=false → 404。 */
    CRITERIA_NOT_FOUND("BETA_PERK_010", "対象の付与条件が定義されていません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
