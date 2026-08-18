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

    /**
     * カテゴリが見つからない（404）。
     *
     * <p>かつては「不在」「名称重複」「非アクティブカテゴリ」の3意味に流用されていたが分割した。
     * 本コードは<b>不在</b>専用。名称重複は {@link #SKILL_009}（409）、非アクティブカテゴリへの
     * 登録は {@link #SKILL_010}（409）を使うこと。</p>
     */
    SKILL_001("SKILL_001", "カテゴリが見つかりません", Severity.WARN),

    /** 資格が見つからない（404） */
    SKILL_002("SKILL_002", "資格が見つかりません", Severity.WARN),

    /**
     * アクセス権限がない（400）。
     *
     * <p><b>これは「意味が割れている」のではなく意図的な集約である。分割してはならない。</b>
     * 本コードは (1) 要求スコープ（村／組織／チーム）の外にあるリソースへのアクセス と
     * (2) 本人以外による操作の権限拒否 の両方に使われる。この2つを別コード・別ステータスに
     * 分けると、応答の差から「そのIDのリソースは他スコープに実在する」ことを外部から判定できる
     * 存在オラクルになる。畳み込んだうえで入力値不正として 400 を返すことで秘匿を達成している
     * （{@code MemberSkillScopeContractIT} が 400 を契約として固定している）。</p>
     */
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
    SKILL_008("SKILL_008", "CSVエクスポートに失敗しました", Severity.ERROR),

    /** 同一スコープ内でカテゴリ名が重複している（409・既存リソースとの状態競合） */
    SKILL_009("SKILL_009", "同じ名前のカテゴリが既にあります", Severity.WARN),

    /**
     * 非アクティブなカテゴリに資格を登録しようとした（409）。
     *
     * <p>カテゴリは実在するが {@code isActive=false} で操作を受け付けない状態競合。
     * 不在（{@link #SKILL_001}・404）とは区別する。</p>
     */
    SKILL_010("SKILL_010", "このカテゴリは現在利用できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
