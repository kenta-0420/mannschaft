package com.mannschaft.app.survey.entity;

import com.mannschaft.app.survey.QuestionType;
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

import java.time.LocalDateTime;

/**
 * アンケート設問エンティティ。アンケートに含まれる各設問を管理する。
 */
@Entity
@Table(name = "survey_questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SurveyQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long surveyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionType questionType;

    @Column(nullable = false, length = 500)
    private String questionText;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRequired = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    private Integer maxSelections;

    private Integer scaleMin;

    private Integer scaleMax;

    @Column(length = 50)
    private String scaleMinLabel;

    @Column(length = 50)
    private String scaleMaxLabel;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 設問テキストを変更する。
     *
     * @param questionText 新しい設問テキスト
     */
    public void changeQuestionText(String questionText) {
        this.questionText = questionText;
    }

    /**
     * 表示順を変更する。
     *
     * @param displayOrder 新しい表示順
     */
    public void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
