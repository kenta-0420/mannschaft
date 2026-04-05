package com.mannschaft.app.forms.entity;

import com.mannschaft.app.forms.FormFieldType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * フォーム提出値エンティティ。提出に含まれる各フィールドの値を管理する。
 */
@Entity
@Table(name = "form_submission_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FormSubmissionValueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long submissionId;

    @Column(nullable = false, length = 50)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FormFieldType fieldType;

    @Column(columnDefinition = "TEXT")
    private String textValue;

    @Column(precision = 15, scale = 4)
    private BigDecimal numberValue;

    private LocalDate dateValue;

    @Column(length = 500)
    private String fileKey;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAutoFilled = false;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
