package com.mannschaft.app.organization;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.2 組織管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum OrgErrorCode implements ErrorCode {

    /** 組織が見つかりません */
    ORG_001("ORG_001", "組織が見つかりません", Severity.WARN),

    /** 組織名は既に使用されています */
    ORG_002("ORG_002", "組織名は既に使用されています", Severity.WARN),

    /** 組織はアーカイブ済みです */
    ORG_003("ORG_003", "組織はアーカイブ済みです", Severity.WARN),

    /** 組織の最大階層深度を超えています */
    ORG_004("ORG_004", "組織の最大階層深度を超えています", Severity.WARN),

    /** この操作を行う権限がありません */
    ORG_005("ORG_005", "この操作を行う権限がありません", Severity.WARN),

    /** 組織は論理削除されていません */
    ORG_006("ORG_006", "組織は削除されていないため復元できません", Severity.WARN),

    /** 既にこの組織に所属しています */
    ORG_007("ORG_007", "既にこの組織に所属しています", Severity.WARN),

    // --- F01.2 拡張プロフィール ---

    /** URLのスキームが不正です（http/https のみ許可）*/
    ORG_040("ORG_040", "URLのスキームが不正です（http/https のみ許可）", Severity.WARN),

    /** 役員の登録件数が上限（50件）を超えています */
    ORG_041("ORG_041", "役員の登録件数が上限（50件）を超えています", Severity.WARN),

    /** 役員の並び替えリクエストが古くなっています */
    ORG_042("ORG_042", "役員の並び替えリクエストが古くなっています。最新の一覧を取得して再送してください", Severity.WARN),

    /** カスタムフィールドの登録件数が上限（20件）を超えています */
    ORG_043("ORG_043", "カスタムフィールドの登録件数が上限（20件）を超えています", Severity.WARN),

    /** カスタムフィールドの並び替えリクエストが古くなっています */
    ORG_044("ORG_044", "カスタムフィールドの並び替えリクエストが古くなっています。最新の一覧を取得して再送してください", Severity.WARN),

    /** 設立日と精度はセットで指定してください */
    ORG_045("ORG_045", "設立日と精度はセットで指定してください（片方のみの指定は不可）", Severity.WARN),

    /** テキストが長すぎます */
    ORG_046("ORG_046", "テキストが長すぎます（上限を超えています）", Severity.WARN),

    /** profile_visibility に不明なキーが含まれています */
    ORG_047("ORG_047", "profile_visibility に不明なキーが含まれています", Severity.WARN),

    /** この操作はADMINまたはDEPUTY_ADMINのみ実行できます */
    ORG_048("ORG_048", "この操作はADMINまたはDEPUTY_ADMINのみ実行できます", Severity.WARN),

    /** 拡張プロフィール項目は /profile エンドポイントで更新してください */
    ORG_049("ORG_049", "拡張プロフィール項目は /profile エンドポイントで更新してください", Severity.WARN),

    /** 役員が見つかりません */
    ORG_050("ORG_050", "役員が見つかりません", Severity.WARN),

    /** カスタムフィールドが見つかりません */
    ORG_051("ORG_051", "カスタムフィールドが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
