package com.mannschaft.app.filesharing.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.filesharing.FileScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 共有フォルダエンティティ。フォルダの階層構造とスコープを管理する。
 */
@Entity
@Table(name = "shared_folders")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SharedFolderEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileScopeType scopeType;

    private Long teamId;

    private Long organizationId;

    private Long userId;

    private Long parentId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String description;

    private Long createdBy;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * フォルダ名を変更する。
     *
     * @param name 新しいフォルダ名
     */
    public void changeName(String name) {
        this.name = name;
    }

    /**
     * 説明を変更する。
     *
     * @param description 新しい説明
     */
    public void changeDescription(String description) {
        this.description = description;
    }

    /**
     * 親フォルダを変更する（移動）。
     *
     * @param parentId 新しい親フォルダID
     */
    public void moveToParent(Long parentId) {
        this.parentId = parentId;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
