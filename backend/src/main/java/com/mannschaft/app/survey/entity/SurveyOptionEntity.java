package com.mannschaft.app.survey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * アンケート選択肢エンティティ。設問に含まれる選択肢を管理する。
 */
@Entity
@Table(name = "survey_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SurveyOptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long questionId;

    @Column(nullable = false, length = 200)
    private String optionText;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /**
     * 選択肢テキストを変更する。
     *
     * @param optionText 新しい選択肢テキスト
     */
    public void changeOptionText(String optionText) {
        this.optionText = optionText;
    }
}
