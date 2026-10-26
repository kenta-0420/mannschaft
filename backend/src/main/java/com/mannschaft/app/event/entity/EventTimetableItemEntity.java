package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.event.dto.UpdateTimetableItemRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * イベントタイムテーブル項目エンティティ。イベントのプログラム構成を管理する。
 */
@Entity
@Table(name = "event_timetable_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class EventTimetableItemEntity extends BaseEntity {

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String speaker;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @Column(length = 200)
    private String location;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * タイムテーブル項目の更新内容を managed entity へ直接適用する。
     * toBuilder().build() による id=null INSERT 化バグを回避するため、フィールドを直接ミューテートする。
     * null フィールドは「変更なし」とみなし、既存値を維持する。
     *
     * @param request 更新リクエスト
     */
    public void applyUpdate(UpdateTimetableItemRequest request) {
        if (request.getTitle() != null) {
            this.title = request.getTitle();
        }
        if (request.getDescription() != null) {
            this.description = request.getDescription();
        }
        if (request.getSpeaker() != null) {
            this.speaker = request.getSpeaker();
        }
        if (request.getStartAt() != null) {
            this.startAt = request.getStartAt();
        }
        if (request.getEndAt() != null) {
            this.endAt = request.getEndAt();
        }
        if (request.getLocation() != null) {
            this.location = request.getLocation();
        }
        if (request.getSortOrder() != null) {
            this.sortOrder = request.getSortOrder();
        }
    }

    /**
     * 並び替え用のソート順を managed entity へ直接適用する。
     *
     * @param order 新しいソート順
     */
    public void applySortOrder(int order) {
        this.sortOrder = order;
    }
}
