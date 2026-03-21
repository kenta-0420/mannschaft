package com.mannschaft.app.matching.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 募集テンプレートエンティティ。
 */
@Entity
@Table(name = "match_request_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MatchRequestTemplateEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, columnDefinition = "JSON")
    private String templateJson;

    /**
     * テンプレートを更新する。
     */
    public void update(String name, String templateJson) {
        this.name = name;
        this.templateJson = templateJson;
    }
}
