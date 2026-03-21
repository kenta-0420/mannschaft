package com.mannschaft.app.service.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.service.ServiceRecordStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * サービス履歴レコードエンティティ。
 */
@Entity
@Table(name = "service_records")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ServiceRecordEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long memberUserId;

    private Long staffUserId;

    @Column(nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String note;

    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ServiceRecordStatus status = ServiceRecordStatus.DRAFT;

    private LocalDateTime deletedAt;

    /**
     * 記録を更新する。
     */
    public void update(Long memberUserId, Long staffUserId, LocalDate serviceDate,
                       String title, String note, Integer durationMinutes) {
        this.memberUserId = memberUserId;
        this.staffUserId = staffUserId;
        this.serviceDate = serviceDate;
        this.title = title;
        this.note = note;
        this.durationMinutes = durationMinutes;
    }

    /**
     * 下書きを確定する。
     */
    public void confirm() {
        this.status = ServiceRecordStatus.CONFIRMED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
