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

    /** プロジェクト名が重複している（409: 同名重複という状態競合） */
    PROJECT_TITLE_DUPLICATE("TODO_002", "同じスコープ内に同名のプロジェクトが既に存在します", Severity.WARN),

    /** ACTIVEプロジェクト数が上限に達している（409: 件数上限という状態競合） */
    PROJECT_LIMIT_EXCEEDED("TODO_003", "ACTIVEプロジェクトの上限（20件）に達しています", Severity.WARN),

    /** PRIVATEはPERSONALスコープのみ許可 */
    PRIVATE_ONLY_FOR_PERSONAL("TODO_004", "PRIVATE公開範囲は個人スコープのみ設定可能です", Severity.WARN),

    /** プロジェクトは既に完了している（409: 状態競合） */
    PROJECT_ALREADY_COMPLETED("TODO_005", "プロジェクトは既に完了しています", Severity.WARN),

    /** プロジェクトは完了状態ではない（409: 状態競合） */
    PROJECT_NOT_COMPLETED("TODO_006", "プロジェクトは完了状態ではありません", Severity.WARN),

    /** マイルストーンが見つからない（IDOR 秘匿 → 404） */
    MILESTONE_NOT_FOUND("TODO_007", "マイルストーンが見つかりません", Severity.WARN),

    /** マイルストーン名が重複している（409: 同名重複という状態競合） */
    MILESTONE_TITLE_DUPLICATE("TODO_008", "同じプロジェクト内に同名のマイルストーンが既に存在します", Severity.WARN),

    /** マイルストーン数が上限に達している（409: 件数上限という状態競合） */
    MILESTONE_LIMIT_EXCEEDED("TODO_009", "マイルストーンの上限（20件）に達しています", Severity.WARN),

    /** TODOが見つからない */
    TODO_NOT_FOUND("TODO_010", "TODOが見つかりません", Severity.WARN),

    /** スコープ整合性違反（TODOとプロジェクトのスコープ不一致） */
    SCOPE_MISMATCH("TODO_011", "TODOとプロジェクトのスコープが一致しません", Severity.WARN),

    /** マイルストーンがプロジェクトに属していない（他プロジェクトの ID を指した越境を不在と同一視 → 404） */
    MILESTONE_NOT_IN_PROJECT("TODO_012", "マイルストーンは指定されたプロジェクトに属していません", Severity.WARN),

    /** プロジェクトなしのTODOにマイルストーンを設定しようとした */
    MILESTONE_REQUIRES_PROJECT("TODO_013", "マイルストーンはプロジェクトに紐付くTODOのみ設定可能です", Severity.WARN),

    /** 担当者が既に割り当て済み（409: 割当重複という状態競合） */
    ASSIGNEE_ALREADY_EXISTS("TODO_014", "担当者は既に割り当てられています", Severity.WARN),

    /** 担当者が見つからない（404: 割当不在） */
    ASSIGNEE_NOT_FOUND("TODO_015", "担当者の割り当てが見つかりません", Severity.WARN),

    /** コメントが見つからない（IDOR 秘匿 → 404） */
    COMMENT_NOT_FOUND("TODO_016", "コメントが見つかりません", Severity.WARN),

    /** コメントは本人のみ編集可能（存在は隠さず作成者以外を拒否 → 403） */
    COMMENT_NOT_OWNER("TODO_017", "コメントは作成者のみ編集可能です", Severity.WARN),

    /** 一括操作のサイズ制限超過 */
    BULK_SIZE_EXCEEDED("TODO_018", "一括操作は最大50件までです", Severity.WARN),

    /** マイルストーンは既に完了している（409: 状態競合） */
    MILESTONE_ALREADY_COMPLETED("TODO_019", "マイルストーンは既に完了しています", Severity.WARN),

    /** 子TODO階層の上限（3階層）を超過（409: 既存階層深さとの状態競合） */
    MAX_DEPTH_EXCEEDED("TODO_020", "これ以上子課題を追加できません（最大3階層）", Severity.WARN),

    /** 親TODOとスコープが不一致 */
    PARENT_SCOPE_MISMATCH("TODO_021", "親課題と同じスコープ内でのみ子課題を作成できます", Severity.WARN),

    /** 子TODO数が上限（50件）に達している（409: 件数上限という状態競合） */
    CHILD_LIMIT_EXCEEDED("TODO_022", "子課題の上限（50件）に達しています", Severity.WARN),

    /** 開始日は終了日（期限日）以前でなければならない */
    START_DATE_AFTER_DUE_DATE("TODO_030", "開始日は終了日（期限日）以前でなければなりません", Severity.WARN),

    /** 連携スケジュールとTODOのスコープが一致しない */
    SCHEDULE_SCOPE_MISMATCH("TODO_031", "連携スケジュールとTODOのスコープが一致しません", Severity.WARN),

    /** スケジュールは既に別のTODOと連携されている（409: 連携重複という状態競合） */
    SCHEDULE_ALREADY_LINKED("TODO_032", "このスケジュールは既に別のTODOと連携されています", Severity.WARN),

    /** TODOは既に別のスケジュールと連携されている（409: 連携重複という状態競合） */
    TODO_ALREADY_LINKED("TODO_033", "このTODOは既に別のスケジュールと連携されています", Severity.WARN),

    /** 自動算出モードのTODOの進捗率は子から自動計算される */
    AUTO_PROGRESS_MODE("TODO_040", "自動算出モードのTODOの進捗率は子から自動計算されます", Severity.WARN),

    /** 共有メモが見つからない（IDOR 秘匿 → 404） */
    SHARED_MEMO_NOT_FOUND("TODO_050", "共有メモが見つかりません", Severity.WARN),

    /** 共有メモは作成者のみ編集・削除可能（存在は隠さず作成者以外を拒否 → 403） */
    SHARED_MEMO_NOT_OWNER("TODO_051", "共有メモは作成者のみ編集・削除可能です", Severity.WARN),

    /** 共有メモ件数が上限（500件）に達している（409: 件数上限という状態競合） */
    SHARED_MEMO_LIMIT_EXCEEDED("TODO_052", "共有メモの上限（500件）に達しています", Severity.WARN),

    /** 共有メモの編集可能期間（24時間）を超過している（409: 編集可能期間経過という状態競合） */
    SHARED_MEMO_EDIT_EXPIRED("TODO_053", "共有メモは投稿から24時間以内のみ編集可能です", Severity.WARN),

    /** 個人メモが見つからない（IDOR 秘匿 → 404） */
    PERSONAL_MEMO_NOT_FOUND("TODO_060", "個人メモが見つかりません", Severity.WARN),

    // F02.3.1 カスタムステータスラベル
    // ※ 名称は設計書 docs/features/F02.3.1_todo_status_labels_and_handoff.md を正とする。
    //    F02.3.1 後続-⑥ C-4 で実装側を設計書に揃えた（エラーコード文字列 "TODO_xxx" は変更なし）。
    /** ラベル名がスコープ内で重複 */
    LABEL_NAME_DUPLICATED("TODO_070", "同じスコープ内に同名のステータスラベルが既に存在します", Severity.WARN),

    /** スコープあたりのラベル数上限超過（20件） */
    LABEL_LIMIT_EXCEEDED("TODO_071", "ステータスラベルの上限（20件）に達しています", Severity.WARN),

    /** 使用中のラベルは削除不可 */
    LABEL_IN_USE("TODO_072", "使用中のステータスラベルは削除できません", Severity.WARN),

    /** SYSTEM 既定ラベルは編集・削除不可 */
    SYSTEM_LABEL_IMMUTABLE("TODO_073", "システム既定ラベルは編集・削除できません", Severity.WARN),

    /** ラベルのスコープが TODO のスコープと一致しない */
    LABEL_SCOPE_MISMATCH("TODO_074", "指定したラベルはこの TODO のスコープでは使用できません", Severity.WARN),

    /** status と statusLabelId のバケットが一致しない */
    STATUS_LABEL_BUCKET_MISMATCH("TODO_075", "指定された status とラベルのバケットが一致しません", Severity.WARN),

    /** ステータスラベルが見つからない */
    STATUS_LABEL_NOT_FOUND("TODO_076", "ステータスラベルが見つかりません", Severity.WARN),

    // F02.3.1 Phase 2 キャッチボール
    /** 個人 TODO はキャッチボール不可 */
    HANDOFF_NOT_ALLOWED_FOR_PERSONAL("TODO_080", "個人TODOはキャッチボールできません", Severity.WARN),

    /** 宛先メンバーがスコープに属していない */
    HANDOFF_RECIPIENT_NOT_MEMBER("TODO_081", "宛先メンバーが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
