package com.mannschaft.app.team;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.2 チーム管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TeamErrorCode implements ErrorCode {

    /** チームが見つかりません */
    TEAM_001("TEAM_001", "チームが見つかりません", Severity.WARN),

    /** チームはアーカイブ済みです */
    TEAM_002("TEAM_002", "チームはアーカイブ済みです", Severity.WARN),

    /** 既にこのチームに所属しています */
    TEAM_003("TEAM_003", "既にこのチームに所属しています", Severity.WARN),

    /** ブロックされているため参加できません */
    TEAM_004("TEAM_004", "ブロックされているため参加できません", Severity.WARN),

    /** この操作を行う権限がありません */
    TEAM_005("TEAM_005", "この操作を行う権限がありません", Severity.WARN),

    /** チームは論理削除されていません */
    TEAM_006("TEAM_006", "チームは削除されていないため復元できません", Severity.WARN),

    // --- F01.2 拡張プロフィール ---

    /** URLのスキームが不正です（http/https のみ許可）*/
    TEAM_040("TEAM_040", "URLのスキームが不正です（http/https のみ許可）", Severity.WARN),

    /** 役員の登録件数が上限（50件）を超えています */
    TEAM_041("TEAM_041", "役員の登録件数が上限（50件）を超えています", Severity.WARN),

    /** 役員の並び替えリクエストが古くなっています */
    TEAM_042("TEAM_042", "役員の並び替えリクエストが古くなっています。最新の一覧を取得して再送してください", Severity.WARN),

    /** カスタムフィールドの登録件数が上限（20件）を超えています */
    TEAM_043("TEAM_043", "カスタムフィールドの登録件数が上限（20件）を超えています", Severity.WARN),

    /** カスタムフィールドの並び替えリクエストが古くなっています */
    TEAM_044("TEAM_044", "カスタムフィールドの並び替えリクエストが古くなっています。最新の一覧を取得して再送してください", Severity.WARN),

    /** 設立日と精度はセットで指定してください */
    TEAM_045("TEAM_045", "設立日と精度はセットで指定してください（片方のみの指定は不可）", Severity.WARN),

    /** テキストが長すぎます */
    TEAM_046("TEAM_046", "テキストが長すぎます（上限を超えています）", Severity.WARN),

    /** profile_visibility に不明なキーが含まれています */
    TEAM_047("TEAM_047", "profile_visibility に不明なキーが含まれています", Severity.WARN),

    /** この操作はADMINまたはDEPUTY_ADMINのみ実行できます */
    TEAM_048("TEAM_048", "この操作はADMINまたはDEPUTY_ADMINのみ実行できます", Severity.WARN),

    /** 拡張プロフィール項目は /profile エンドポイントで更新してください */
    TEAM_049("TEAM_049", "拡張プロフィール項目は /profile エンドポイントで更新してください", Severity.WARN),

    /** 役員が見つかりません */
    TEAM_050("TEAM_050", "役員が見つかりません", Severity.WARN),

    /** カスタムフィールドが見つかりません */
    TEAM_051("TEAM_051", "カスタムフィールドが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
