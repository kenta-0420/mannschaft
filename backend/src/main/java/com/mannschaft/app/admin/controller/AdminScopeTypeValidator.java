package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.ScopeType;

/**
 * 管理系 Controller が受け取る {@code scopeType} リクエストパラメータの検証ヘルパー
 * （認可根治戦役 Wave5 追込）。
 *
 * <h3>解決する問題</h3>
 * <p>{@code AdminDashboardController} / {@code AdminFeedbackController} /
 * {@code AdminPermissionGroupController} は、攻撃者が自由に制御できる {@code scopeType}
 * を検証せずそのまま {@code accessControlService.checkAdminOrAbove} へ渡していた。
 * 同メソッドは内部で {@code ScopeType.valueOf(scopeType)}
 * （{@code AccessControlService.java:239} 等）を呼ぶため、{@code GENERAL} のような
 * 未定義値では {@code IllegalArgumentException} が送出される。
 * {@code GlobalExceptionHandler} に当該ハンドラが無いため、これは <b>500</b> として表面化していた。</p>
 *
 * <p>認可としては fail-closed（例外で中断するため越境は起きない）ので穴ではないが、
 * 攻撃者が制御する入力で未処理の 500 が出るのは
 * (1) ログ・監視のノイズ源になる (2) スタックトレース経由の情報漏洩リスクを抱える
 * (3) クライアントが「サーバー障害」と誤認する、という理由から
 * <b>400（{@code COMMON_001} = 入力内容に不備があります）へ正規化</b>する。</p>
 *
 * <h3>挙動</h3>
 * <p>{@link ScopeType} は {@code TEAM} / {@code ORGANIZATION} の 2 値のみで構成されるため、
 * 本ヘルパーの許可集合は enum 定義と常に一致する（ホワイトリストを二重管理しない）。
 * 既存の正当系（{@code TEAM} / {@code ORGANIZATION}）の挙動は一切変えない。</p>
 *
 * <p>本クラスは<b>入力検証であって認可ではない</b>。認可番人
 * （{@code AuthzControllerGuardArchTest}）の白名簿と誤認されないよう、
 * 意図的に {@code *AccessGuard} / {@code *AccessService} を避けた命名としている。
 * 認可は従来どおり {@code accessControlService.checkAdminOrAbove} が担う。</p>
 */
public final class AdminScopeTypeValidator {

    private AdminScopeTypeValidator() {
        // ユーティリティクラス
    }

    /**
     * {@code scopeType} が {@link ScopeType} として解釈可能であることを要求する。
     * 不正値・null・空文字は 400（{@code COMMON_001}）で中断する。
     *
     * <p>認可の<b>前</b>に呼ぶこと（{@code checkAdminOrAbove} へ到達する前に弾く）。</p>
     *
     * @param scopeType リクエストパラメータ由来のスコープ種別文字列
     * @throws BusinessException {@code COMMON_001}（400）— 未定義のスコープ種別の場合
     */
    public static void requireSupportedScopeType(String scopeType) {
        if (!isSupportedScopeType(scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    /**
     * {@code scopeType} が {@link ScopeType} として解釈可能かを返す（例外を投げない判定版）。
     *
     * <p>entity 由来の scope を扱う経路（例:
     * {@code AdminFeedbackController.authorizeByEntityScope}）では、
     * 管轄外スコープを 400 ではなく <b>404 で存在秘匿</b>したいため、
     * throw 版ではなく本メソッドで分岐する。</p>
     *
     * @param scopeType スコープ種別文字列（null 可）
     * @return {@code TEAM} / {@code ORGANIZATION} のいずれかなら true
     */
    public static boolean isSupportedScopeType(String scopeType) {
        if (scopeType == null || scopeType.isBlank()) {
            return false;
        }
        for (ScopeType supported : ScopeType.values()) {
            if (supported.name().equals(scopeType)) {
                return true;
            }
        }
        return false;
    }
}
