package com.mannschaft.app.todo;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F02.3 TODO管理・プロジェクト進捗のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TodoErrorCode implements ErrorCode {

    /** プロジェクトが見つからない */
    PROJECT_NOT_FOUND("TODO_001", "プロジェクトが見つかりません", Severity.WARN),

    /** プロジェクト名が重複している */
    PROJECT_TITLE_DUPLICATE("TODO_002", "同じスコープ内に同名のプロジェクトが既に存在します", Severity.WARN),

    /** ACTIVEプロジェクト数が上限に達している */
    PROJECT_LIMIT_EXCEEDED("TODO_003", "ACTIVEプロジェクトの上限（20件）に達しています", Severity.WARN),

    /** PRIVATEはPERSONALスコープのみ許可 */
    PRIVATE_ONLY_FOR_PERSONAL("TODO_004", "PRIVATE公開範囲は個人スコープのみ設定可能です", Severity.WARN),

    /** プロジェクトは既に完了している */
    PROJECT_ALREADY_COMPLETED("TODO_005", "プロジェクトは既に完了しています", Severity.WARN),

    /** プロジェクトは完了状態ではない */
    PROJECT_NOT_COMPLETED("TODO_006", "プロジェクトは完了状態ではありません", Severity.WARN),

    /** マイルストーンが見つからない */
    MILESTONE_NOT_FOUND("TODO_007", "マイルストーンが見つかりません", Severity.WARN),

    /** マイルストーン名が重複している */
    MILESTONE_TITLE_DUPLICATE("TODO_008", "同じプロジェクト内に同名のマイルストーンが既に存在します", Severity.WARN),

    /** マイルストーン数が上限に達している */
    MILESTONE_LIMIT_EXCEEDED("TODO_009", "マイルストーンの上限（20件）に達しています", Severity.WARN),

    /** TODOが見つからない */
    TODO_NOT_FOUND("TODO_010", "TODOが見つかりません", Severity.WARN),

    /** スコープ整合性違反（TODOとプロジェクトのスコープ不一致） */
    SCOPE_MISMATCH("TODO_011", "TODOとプロジェクトのスコープが一致しません", Severity.WARN),

    /** マイルストーンがプロジェクトに属していない */
    MILESTONE_NOT_IN_PROJECT("TODO_012", "マイルストーンは指定されたプロジェクトに属していません", Severity.WARN),

    /** プロジェクトなしのTODOにマイルストーンを設定しようとした */
    MILESTONE_REQUIRES_PROJECT("TODO_013", "マイルストーンはプロジェクトに紐付くTODOのみ設定可能です", Severity.WARN),

    /** 担当者が既に割り当て済み */
    ASSIGNEE_ALREADY_EXISTS("TODO_014", "担当者は既に割り当てられています", Severity.WARN),

    /** 担当者が見つからない */
    ASSIGNEE_NOT_FOUND("TODO_015", "担当者の割り当てが見つかりません", Severity.WARN),

    /** コメントが見つからない */
    COMMENT_NOT_FOUND("TODO_016", "コメントが見つかりません", Severity.WARN),

    /** コメントは本人のみ編集可能 */
    COMMENT_NOT_OWNER("TODO_017", "コメントは作成者のみ編集可能です", Severity.WARN),

    /** 一括操作のサイズ制限超過 */
    BULK_SIZE_EXCEEDED("TODO_018", "一括操作は最大50件までです", Severity.WARN),

    /** マイルストーンは既に完了している */
    MILESTONE_ALREADY_COMPLETED("TODO_019", "マイルストーンは既に完了しています", Severity.WARN),

    /** 子TODO階層の上限（3階層）を超過 */
    MAX_DEPTH_EXCEEDED("TODO_020", "これ以上子課題を追加できません（最大3階層）", Severity.WARN),

    /** 親TODOとスコープが不一致 */
    PARENT_SCOPE_MISMATCH("TODO_021", "親課題と同じスコープ内でのみ子課題を作成できます", Severity.WARN),

    /** 子TODO数が上限（50件）に達している */
    CHILD_LIMIT_EXCEEDED("TODO_022", "子課題の上限（50件）に達しています", Severity.WARN),

    /** 開始日は終了日（期限日）以前でなければならない */
    START_DATE_AFTER_DUE_DATE("TODO_030", "開始日は終了日（期限日）以前でなければなりません", Severity.WARN),

    /** 連携スケジュールとTODOのスコープが一致しない */
    SCHEDULE_SCOPE_MISMATCH("TODO_031", "連携スケジュールとTODOのスコープが一致しません", Severity.WARN),

    /** スケジュールは既に別のTODOと連携されている */
    SCHEDULE_ALREADY_LINKED("TODO_032", "このスケジュールは既に別のTODOと連携されています", Severity.WARN),

    /** TODOは既に別のスケジュールと連携されている */
    TODO_ALREADY_LINKED("TODO_033", "このTODOは既に別のスケジュールと連携されています", Severity.WARN),

    /** 自動算出モードのTODOの進捗率は子から自動計算される */
    AUTO_PROGRESS_MODE("TODO_040", "自動算出モードのTODOの進捗率は子から自動計算されます", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
