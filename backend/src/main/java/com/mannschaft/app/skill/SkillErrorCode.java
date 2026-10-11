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
     * 【非推奨・未使用】アクセス権限がない。
     *
     * <p>CMP-261010-2226 で資格 API の拒否は次に統一したため、本コードはどこからも投げられない。
     * 同チーム内の権限不足（本人でも管理者でもない所属者）は {@code COMMON_002}（403）、
     * 越境（非メンバー）は不在と完全一致の {@link #SKILL_002}（404）。
     * 越境と権限不足を別コードにすると、応答の差から ID の実在が判る存在オラクルになるため、
     * 判定は {@code ScopeConcealingAccessGate} に集約している。</p>
     *
     * <p>注意: 以前の説明にあった「複数の理由を1コードに畳み、status だけ不在側に揃えれば秘匿できる」は誤り。
     * 秘匿には status・{@code error.code}・{@code error.message} のすべてが不在時と一致する必要がある。
     * enum 定数は互換のため残している（GlobalExceptionHandler の写像も同様）。新規に使ってはならない。</p>
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
