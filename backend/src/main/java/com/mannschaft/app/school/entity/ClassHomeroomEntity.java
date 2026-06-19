package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 学級担任マッピング（クラスチームと担任の対応）。 */
@Entity
@Table(name = "class_homerooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ClassHomeroomEntity extends BaseEntity {

    /** FK → teams.id（クラスチーム） */
    @Column(nullable = false)
    private Long teamId;

    /** FK → users.id（学級担任） */
    @Column(nullable = false)
    private Long homeroomTeacherUserId;

    /** 副担任配列 [123, 456]（最大3名、JSON） */
    @Column(columnDefinition = "JSON")
    private String assistantTeacherUserIds;

    /** 年度 */
    @Column(nullable = false)
    private Integer academicYear;

    /** 有効開始日 */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /** 有効終了日（NULL=現役） */
    private LocalDate effectiveUntil;

    /** 作成者 */
    @Column(nullable = false)
    private Long createdBy;

    /**
     * 学級担任設定を更新する（直接ミューテート）。
     *
     * <p>{@code toBuilder().build()} で作り直すと {@link com.mannschaft.app.common.BaseEntity}
     * の {@code id} が引き継がれず id=null の新インスタンスとなり、INSERT 化して行が重複する。
     * managed entity を直接書き換えることで JPA dirty checking が UPDATE を発行し id を保持する。
     * null フィールドは既存値を維持する（部分更新）。</p>
     *
     * @param homeroomTeacherUserId      新しい学級担任ユーザーID（null = 変更なし）
     * @param assistantTeacherUserIds    新しい副担任リスト JSON（null = 変更なし）
     * @param effectiveUntil             新しい有効終了日（null = 変更なし）
     */
    public void applyUpdate(
            Long homeroomTeacherUserId,
            String assistantTeacherUserIds,
            java.time.LocalDate effectiveUntil) {
        if (homeroomTeacherUserId != null) this.homeroomTeacherUserId = homeroomTeacherUserId;
        if (assistantTeacherUserIds != null) this.assistantTeacherUserIds = assistantTeacherUserIds;
        if (effectiveUntil != null) this.effectiveUntil = effectiveUntil;
    }
}
