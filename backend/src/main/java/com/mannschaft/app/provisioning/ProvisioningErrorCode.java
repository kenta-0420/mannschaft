package com.mannschaft.app.provisioning;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 柱②-2: 販促プロビジョニング機能のエラーコード定義。
 *
 * <p>本 PR では試練（受け入れテスト）を先行設置する。実装は後続 PR（出陣）で行う。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ProvisioningErrorCode implements ErrorCode {

    /** 招待が見つかりません */
    PROV_001("PROV_001", "招待が見つかりません", Severity.WARN),

    /** 招待の有効期限が切れています */
    PROV_002("PROV_002", "招待の有効期限が切れています", Severity.WARN),

    /** 招待は既に取消/失効しています */
    PROV_003("PROV_003", "招待は既に取消または失効しています", Severity.WARN),

    /** 招待メールアドレスが不正です */
    PROV_004("PROV_004", "招待メールアドレスが不正です", Severity.WARN),

    /** スコープ指定が不正です（team/organization の XOR 違反） */
    PROV_005("PROV_005", "対象スコープの指定が不正です", Severity.WARN),

    /** 招待メールアドレスとログインユーザーの検証済みメールアドレスが一致しません */
    PROV_006("PROV_006", "招待メールアドレスとログインユーザーのメールアドレスが一致しません", Severity.WARN),

    /** この操作は SYSTEM_ADMIN のみ実行できます */
    PROV_007("PROV_007", "この操作を行う権限がありません", Severity.WARN),

    /** PROVISIONED 状態のスコープでは利用できません */
    PROV_008("PROV_008", "この操作は現在利用できません", Severity.WARN),

    /** 対象が見つかりません（PROVISIONED 秘匿込みの一律 404） */
    PROV_009("PROV_009", "対象が見つかりません", Severity.WARN),

    /** 招待は既に承諾済みです（承諾者本人以外からの再承諾） */
    PROV_010("PROV_010", "招待が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
