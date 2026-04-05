package com.mannschaft.app.queue.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.queue.QueueMode;
import com.mannschaft.app.queue.QueueScopeType;
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

import java.time.LocalDateTime;

/**
 * 順番待ちカテゴリエンティティ。順番待ちの分類単位を管理する。
 */
@Entity
@Table(name = "queue_categories")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class QueueCategoryEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueueScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private QueueMode queueMode = QueueMode.INDIVIDUAL;

    @Column(length = 5)
    private String prefixChar;

    @Column(nullable = false)
    @Builder.Default
    private Short maxQueueSize = 50;

    @Column(nullable = false)
    @Builder.Default
    private Short displayOrder = 0;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * カテゴリ情報を更新する。
     *
     * @param name         カテゴリ名
     * @param queueMode    キューモード
     * @param prefixChar   プレフィックス文字
     * @param maxQueueSize 最大キューサイズ
     * @param displayOrder 表示順
     */
    public void update(String name, QueueMode queueMode, String prefixChar,
                       Short maxQueueSize, Short displayOrder) {
        this.name = name;
        this.queueMode = queueMode;
        this.prefixChar = prefixChar;
        this.maxQueueSize = maxQueueSize;
        this.displayOrder = displayOrder;
    }
}
