package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 個人スケジュール更新リクエストDTO。全フィールドnullable（部分更新）。
 *
 * <p>機能55 BE対応: absoluteReminders を追加。
 * null = 変更なし、空リスト = 絶対リマインダー全削除のセマンティクス。
 * 相対（reminders）と絶対（absoluteReminders）を合算して最大5件の制約は
 * サービス層（PersonalScheduleService）で検証する。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdatePersonalScheduleRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Size(max = 300)
    private final String location;

    /**
     * 開始日時。クライアントTZ付きで受け取り、JST に変換して保存する（null = 変更なし）。
     */
    private final OffsetDateTime startAt;

    /**
     * 終了日時。クライアントTZ付きで受け取り、JST に変換して保存する（null = 変更なし）。
     */
    private final OffsetDateTime endAt;

    private final Boolean allDay;

    private final String eventType;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private final String color;

    @Size(max = 3)
    private final List<Integer> reminders;

    private final RecurrenceRuleDto recurrenceRule;

    private final String updateScope;

    /**
     * 絶対指定リマインダー（固定日時）の更新リスト（機能55 BE対応）。
     *
     * <p>null = 変更なし（既存の絶対リマインダーを保持）。
     * 空リスト = 既存の絶対リマインダーを全削除。
     * 非空リスト = 既存の絶対リマインダーを全削除して新規登録（差し替え）。
     * OffsetDateTime で受け取りタイムゾーン情報を保持する。
     * 編集コンテキストのため過去日時も許容する。</p>
     */
    private final List<OffsetDateTime> absoluteReminders;

    /**
     * updateScope のデフォルト値を返す。null の場合は THIS_ONLY を返す。
     */
    public String getUpdateScopeOrDefault() {
        return updateScope != null ? updateScope : "THIS_ONLY";
    }
}
