package com.mannschaft.app.resume.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 職歴エンティティ（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §4.4
 *
 * <p>{@code resume_id} で親の {@link ResumeEntity} と紐づく。
 * 1 履歴書に複数行の職歴を保持できる。
 *
 * <p>{@code include_in_rirekisho} / {@code include_in_shokumukeireki} フラグにより、
 * 履歴書・職務経歴書それぞれへの出力対象を個別に制御できる。
 */
@Entity
@Table(name = "resume_careers")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ResumeCareerEntity extends UuidV7Entity {

    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    /** 入社年。 */
    @Column(name = "entry_year", nullable = false)
    private Short entryYear;

    /** 入社月（null = 月不明）。 */
    @Column(name = "entry_month")
    private Byte entryMonth;

    /** 退社年（null = 在職中または不明）。 */
    @Column(name = "end_year")
    private Short endYear;

    /** 退社月（null = 在職中または不明）。 */
    @Column(name = "end_month")
    private Byte endMonth;

    /** 現職フラグ。true の場合は退社年月を空欄扱いにして「現在に至る」と出力する。 */
    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private boolean isCurrent = false;

    /** 会社名。 */
    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;

    /** 部署・役職。 */
    @Column(name = "department", length = 255)
    private String department;

    /** 雇用形態（例: 正社員・契約社員・アルバイト）。 */
    @Column(name = "employment_type", length = 50)
    private String employmentType;

    /** 事業内容の概要（職務経歴書用）。 */
    @Column(name = "business_summary", length = 500)
    private String businessSummary;

    /** 職務内容の詳細（職務経歴書用）。 */
    @Column(name = "job_description", columnDefinition = "TEXT")
    private String jobDescription;

    /** 実績・成果（職務経歴書用）。 */
    @Column(name = "achievements", columnDefinition = "TEXT")
    private String achievements;

    /** 履歴書（rirekisho）への出力対象フラグ。デフォルト true。 */
    @Column(name = "include_in_rirekisho", nullable = false)
    @Builder.Default
    private boolean includeInRirekisho = true;

    /** 職務経歴書（shokumukeireki）への出力対象フラグ。デフォルト true。 */
    @Column(name = "include_in_shokumukeireki", nullable = false)
    @Builder.Default
    private boolean includeInShokumukeireki = true;

    /** 表示順。小さいほど先頭に表示される。 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 内容を更新する。 */
    public void update(Short entryYear, Byte entryMonth, Short endYear, Byte endMonth,
                       boolean isCurrent, String companyName, String department,
                       String employmentType, String businessSummary, String jobDescription,
                       String achievements, boolean includeInRirekisho,
                       boolean includeInShokumukeireki, int displayOrder) {
        this.entryYear = entryYear;
        this.entryMonth = entryMonth;
        this.endYear = endYear;
        this.endMonth = endMonth;
        this.isCurrent = isCurrent;
        this.companyName = companyName;
        this.department = department;
        this.employmentType = employmentType;
        this.businessSummary = businessSummary;
        this.jobDescription = jobDescription;
        this.achievements = achievements;
        this.includeInRirekisho = includeInRirekisho;
        this.includeInShokumukeireki = includeInShokumukeireki;
        this.displayOrder = displayOrder;
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
