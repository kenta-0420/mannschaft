package com.mannschaft.app.village;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F17.1 村機能の専用エラーコード（Phase 1 B3 メンバーシップ範囲）。
 *
 * <p>設計書 {@code docs/features/F17.1_village_community.md} §10 のコード番号と
 * 完全一致させる。HTTP マッピングは {@link com.mannschaft.app.common.GlobalExceptionHandler}
 * の {@code ERROR_CODE_STATUS_MAP} で個別指定する。</p>
 *
 * <p>Phase 1 B3 では下記のメンバーシップ操作で使用するコードを定義する。
 * 他のフェーズ・他の足軽が同じ enum に追加で値を追記する想定。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VillageErrorCode implements ErrorCode {

    /** 村が存在しない / 削除/凍結済み（IDOR 対策で 404 統一） */
    VILLAGE_001("VILLAGE_001", "村が見つかりません", Severity.WARN),

    /** UNLISTED 村に非村人がアクセス（403） */
    VILLAGE_002("VILLAGE_002", "この村は限定公開のためアクセスできません", Severity.WARN),

    /** すでに村人（409） */
    VILLAGE_006("VILLAGE_006", "すでに村人です", Severity.WARN),

    /** 村人ではない（409） */
    VILLAGE_007("VILLAGE_007", "この村のメンバーではありません", Severity.WARN),

    /** 参加村数ハード上限（429） */
    VILLAGE_012("VILLAGE_012", "参加可能な村数の上限（100）を超えました", Severity.WARN),

    /** チーム/組織代表権限なし（403） */
    VILLAGE_015("VILLAGE_015", "この主体として参加する権限がありません", Severity.WARN),

    /** 指定主体が村人でない（403） */
    VILLAGE_016("VILLAGE_016", "指定された主体は村人ではありません", Severity.WARN),

    /** 村長は後継未指名で退村不可（409） */
    VILLAGE_017("VILLAGE_017", "村長は後継を指名するまで退村できません", Severity.WARN),

    /** 楽観ロック競合（409） */
    VILLAGE_018("VILLAGE_018", "他のユーザーが情報を更新しました。最新の内容を確認して再度お試しください", Severity.WARN),

    /** APPROVAL 村に直接参加しようとした（409） */
    VILLAGE_019("VILLAGE_019", "この村は承認が必要です。参加申請をご利用ください", Severity.WARN),

    /** モデレーション権限なし（403） — 村長/長老でないユーザーが BAN や役職変更を試みた */
    VILLAGE_024("VILLAGE_024", "モデレーション権限がありません", Severity.WARN),

    /** 参加/退出のフラッピング検出（409） */
    VILLAGE_025("VILLAGE_025", "短時間に参加と退出を繰り返しています。しばらく時間をおいてからお試しください", Severity.WARN),

    /** 凍結済み村への変更操作（409） */
    VILLAGE_027("VILLAGE_027", "この村は凍結されています", Severity.WARN),

    /**
     * BAN されているメンバーの操作（403）。
     * 設計書 §10 で未定義だった BAN 状態専用コードとして本 Phase で新規割当。
     */
    VILLAGE_031("VILLAGE_031", "この村から BAN されています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
