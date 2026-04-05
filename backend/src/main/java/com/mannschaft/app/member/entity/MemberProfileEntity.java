package com.mannschaft.app.member.entity;

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
 * メンバープロフィールエンティティ。メンバー紹介ページ内の各メンバー情報を管理する。
 */
@Entity
@Table(name = "member_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberProfileEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamPageId;

    private Long userId;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(length = 20)
    private String memberNumber;

    @Column(length = 500)
    private String photoS3Key;

    @Column(length = 500)
    private String bio;

    @Column(length = 100)
    private String position;

    @Column(columnDefinition = "JSON")
    private String customFieldValues;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isVisible = true;

    /**
     * ADMIN/DEPUTY_ADMIN によるプロフィール全体更新。
     */
    public void update(String displayName, String memberNumber, String photoS3Key,
                       String bio, String position, String customFieldValues,
                       Integer sortOrder, Boolean isVisible) {
        this.displayName = displayName;
        this.memberNumber = memberNumber;
        this.photoS3Key = photoS3Key;
        this.bio = bio;
        this.position = position;
        this.customFieldValues = customFieldValues;
        this.sortOrder = sortOrder;
        this.isVisible = isVisible;
    }

    /**
     * MEMBER によるセルフ編集（bio, photoS3Key, customFieldValues のみ）。
     */
    public void selfEdit(String bio, String photoS3Key, String customFieldValues) {
        this.bio = bio;
        this.photoS3Key = photoS3Key;
        this.customFieldValues = customFieldValues;
    }

    /**
     * 表示順を更新する。
     */
    public void updateSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
