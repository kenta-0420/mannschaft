package com.mannschaft.app.school.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** 学級担任設定更新リクエスト DTO。null フィールドは変更しない。 */
@Getter
@NoArgsConstructor
public class ClassHomeroomUpdateRequest {

    /** 新しい学級担任の user_id */
    private Long homeroomTeacherUserId;

    /** 副担任の user_id 配列（最大3名、空リストで全削除） */
    private List<Long> assistantTeacherUserIds;

    /** 有効終了日（任期終了の場合に設定） */
    private LocalDate effectiveUntil;
}
