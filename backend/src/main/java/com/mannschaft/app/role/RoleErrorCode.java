package com.mannschaft.app.role;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.2 ロール・権限管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum RoleErrorCode implements ErrorCode {

    /** ロールが見つかりません */
    ROLE_001("ROLE_001", "ロールが見つかりません", Severity.WARN),

    /** 招待トークンが無効または期限切れです */
    ROLE_002("ROLE_002", "招待トークンが無効または期限切れです", Severity.WARN),

    /** 招待トークンの使用回数上限に達しています */
    ROLE_003("ROLE_003", "招待トークンの使用回数上限に達しています", Severity.WARN),

    /** 最後の管理者を除名・変更できません */
    ROLE_004("ROLE_004", "最後の管理者を除名・変更できません", Severity.WARN),

    /** 上位ロールのユーザーをブロックできません */
    ROLE_005("ROLE_005", "上位ロールのユーザーをブロックできません", Severity.WARN),

    /** 権限グループが見つかりません */
    ROLE_006("ROLE_006", "権限グループが見つかりません", Severity.WARN),

    /** パーミッションが見つかりません */
    ROLE_007("ROLE_007", "パーミッションが見つかりません", Severity.WARN),

    /** QRコードサイズが範囲外です（64〜1024px） */
    ROLE_008("ROLE_008", "QRコードサイズは64〜1024の範囲で指定してください", Severity.WARN),

    /**
     * 宛先不一致（F04.12 / F01.2 承諾型オファー）。
     * 他人宛ての招待・委譲オファーを第三者が承諾/辞退しようとした場合の 403（IDOR 防止）。
     * 発行時に特権ロール（ADMIN/DEPUTY_ADMIN）を指定した場合の 422 にも流用する（設計書 §6・C-1）。
     */
    ROLE_009("ROLE_009", "この招待はあなた宛てではありません", Severity.WARN),

    /**
     * オーナー委譲 承諾フロー: 承諾者の 2FA 未設定（F01.2 承諾型化 / 2026-07-18）。
     * ADMIN 昇格には 2FA 必須（設計書 §承諾フロー step3・§6）。→ 422。
     */
    ROLE_010("ROLE_010", "管理者になるには2段階認証の設定が必要です", Severity.WARN),

    /**
     * オーナー委譲 打診: 同一スコープに有効な PENDING オファーが既存（重複打診防止）。→ 409。
     */
    ROLE_011("ROLE_011", "既に有効な委譲オファーが存在します", Severity.WARN),

    /**
     * オーナー委譲 承諾/辞退: オファーが PENDING でない・期限切れ・発行後に前提が崩れた（状態不整合）。→ 409。
     * 既存 {@code RoleService#transferOwnership} が投げる {@code ROLE_001}（発行者が既に非 ADMIN 等、
     * オファー発行後の状態変化）を承諾フロー文脈へ再マッピングする受け皿でもある（設計書 H-3 §3）。
     */
    ROLE_012("ROLE_012", "委譲オファーは既に処理済みか無効です", Severity.WARN),

    /**
     * オーナー委譲: オファー不在（スコープ不一致の BOLA 含む）・委譲対象が当該スコープ非所属。→ 404。
     */
    ROLE_013("ROLE_013", "対象が見つかりません", Severity.WARN),

    /**
     * オーナー委譲 打診: 委譲対象が不正（自分自身を対象に指定）。→ 422。
     */
    ROLE_014("ROLE_014", "自分自身への委譲はできません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
