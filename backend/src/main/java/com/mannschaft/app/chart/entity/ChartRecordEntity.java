package com.mannschaft.app.chart.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * カルテ本体エンティティ。来店ごとの施術記録を管理する。
 */
@Entity
@Table(name = "chart_records")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartRecordEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long customerUserId;

    private Long staffUserId;

    @Column(nullable = false)
    private LocalDate visitDate;

    @Column(columnDefinition = "TEXT")
    private String chiefComplaint;

    @Column(columnDefinition = "TEXT")
    private String treatmentNote;

    @Column(columnDefinition = "TEXT")
    private String nextRecommendation;

    private LocalDate nextVisitRecommendedDate;

    @Column(columnDefinition = "TEXT")
    private String allergyInfo;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSharedToCustomer = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * カルテの基本情報を更新する。
     */
    public void update(String chiefComplaint, String treatmentNote, String nextRecommendation,
                       LocalDate nextVisitRecommendedDate, String allergyInfo, Long staffUserId) {
        this.chiefComplaint = chiefComplaint;
        this.treatmentNote = treatmentNote;
        this.nextRecommendation = nextRecommendation;
        this.nextVisitRecommendedDate = nextVisitRecommendedDate;
        this.allergyInfo = allergyInfo;
        this.staffUserId = staffUserId;
    }

    /**
     * 顧客共有設定を変更する。
     */
    public void updateShareStatus(boolean isSharedToCustomer) {
        this.isSharedToCustomer = isSharedToCustomer;
    }

    /**
     * ピン留め状態を変更する。
     */
    public void updatePinStatus(boolean isPinned) {
        this.isPinned = isPinned;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
