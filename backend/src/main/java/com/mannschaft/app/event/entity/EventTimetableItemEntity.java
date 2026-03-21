package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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
}
