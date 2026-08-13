package com.mannschaft.app.skill;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スキル・資格管理機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum SkillErrorCode implements ErrorCode {

    /** カテゴリが見つからない（名称重複・非アクティブカテゴリの検出にも流用され意味が割れているため変更見送り） */
    SKILL_001("SKILL_001", "カテゴリが見つかりません", Severity.WARN),

    /** 資格が見つからない（404） */
    SKILL_002("SKILL_002", "資格が見つかりません", Severity.WARN),

    /** アクセス権限がない（スコープ不一致を畳んだ利用と本人以外操作の権限拒否の両方に使われ意味が割れているため変更見送り） */
    SKILL_003("SKILL_003", "アクセス権限がありません", Severity.WARN),

    /** カテゴリは既に削除されている（throw元なし・未使用） */
    SKILL_004("SKILL_004", "カテゴリは既に削除されています", Severity.WARN),

    /** 同一資格が既に登録されている（状態競合のため409） */
    SKILL_005("SKILL_005", "同一資格が既に登録されています", Severity.WARN),

    /** バージョンが一致しない（楽観的ロック・状態競合のため409） */
    SKILL_006("SKILL_006", "バージョンが一致しません（楽観的ロック）", Severity.WARN),

    /** ステータスが不正（承認対象外ステータスでの承認操作・状態競合のため409） */
    SKILL_007("SKILL_007", "ステータスが不正です", Severity.WARN),

    /** CSVエクスポートに失敗 */
    SKILL_008("SKILL_008", "CSVエクスポートに失敗しました", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
