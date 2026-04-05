package com.mannschaft.app.family;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.4 ファミリーチーム・ライフユーティリティのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum FamilyErrorCode implements ErrorCode {

    /** 帰宅予定時刻が過去 */
    FAMILY_001("FAMILY_001", "帰宅予定時刻は未来の日時を指定してください", Severity.WARN),

    /** お出かけ先が未指定 */
    FAMILY_002("FAMILY_002", "行き先を入力してください", Severity.WARN),

    /** ロール呼称変更不可（SYSTEM_ADMIN / GUEST） */
    FAMILY_003("FAMILY_003", "このロールの呼称は変更できません", Severity.WARN),

    /** ロール呼称の文字数超過 */
    FAMILY_004("FAMILY_004", "表示名は50文字以内で入力してください", Severity.WARN),

    /** コイントス選択肢が2〜6個の範囲外 */
    FAMILY_005("FAMILY_005", "選択肢は2〜6個で指定してください", Severity.WARN),

    /** コイントス選択肢の文字数超過 */
    FAMILY_006("FAMILY_006", "各選択肢は50文字以内で入力してください", Severity.WARN),

    /** コイントスレートリミット超過 */
    FAMILY_007("FAMILY_007", "コイントスの実行回数が上限を超えました。しばらくお待ちください", Severity.WARN),

    /** コイントス結果が存在しない */
    FAMILY_008("FAMILY_008", "コイントスの結果が見つかりません", Severity.WARN),

    /** コイントス共有済み */
    FAMILY_009("FAMILY_009", "既にチャットに共有済みです", Severity.WARN),

    /** コイントス共有権限なし */
    FAMILY_010("FAMILY_010", "コイントスの実行者のみ共有できます", Severity.WARN),

    /** お買い物リストが見つからない */
    FAMILY_011("FAMILY_011", "お買い物リストが見つかりません", Severity.WARN),

    /** お買い物リスト数が上限に到達 */
    FAMILY_012("FAMILY_012", "お買い物リスト数が上限（10件）に達しています", Severity.WARN),

    /** お買い物アイテムが見つからない */
    FAMILY_013("FAMILY_013", "アイテムが見つかりません", Severity.WARN),

    /** お買い物アイテム数が上限に到達 */
    FAMILY_014("FAMILY_014", "アイテム数が上限（100件）に達しています", Severity.WARN),

    /** お買い物リスト削除権限なし */
    FAMILY_015("FAMILY_015", "このリストの削除権限がありません", Severity.WARN),

    /** 当番ローテーションが見つからない */
    FAMILY_016("FAMILY_016", "当番ローテーションが見つかりません", Severity.WARN),

    /** 当番ローテーション数が上限に到達 */
    FAMILY_017("FAMILY_017", "当番ローテーション数が上限（10件）に達しています", Severity.WARN),

    /** 記念日が見つからない */
    FAMILY_018("FAMILY_018", "記念日が見つかりません", Severity.WARN),

    /** 記念日数が上限に到達 */
    FAMILY_019("FAMILY_019", "記念日数が上限（50件）に達しています", Severity.WARN),

    /** 壁紙が見つからない */
    FAMILY_020("FAMILY_020", "壁紙が見つかりません", Severity.WARN),

    /** テンプレートリストではない */
    FAMILY_021("FAMILY_021", "指定されたリストはテンプレートリストではありません", Severity.WARN),

    /** コピー先リストがARCHIVED */
    FAMILY_022("FAMILY_022", "アーカイブ済みのリストにはコピーできません", Severity.WARN),

    /** 質問文の文字数超過 */
    FAMILY_023("FAMILY_023", "質問文は200文字以内で入力してください", Severity.WARN),

    /** 不正なロール名 */
    FAMILY_024("FAMILY_024", "不正なロール名です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
