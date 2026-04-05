package com.mannschaft.app.timeline.entity;

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
 * タイムライン投票選択肢エンティティ。投票の選択肢と得票数を管理する。
 */
@Entity
@Table(name = "timeline_poll_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimelinePollOptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long timelinePollId;

    @Column(nullable = false, length = 100)
    private String optionText;

    @Column(nullable = false)
    @Builder.Default
    private Integer voteCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = 0;

    /**
     * 得票数をインクリメントする。
     */
    public void incrementVoteCount() {
        this.voteCount++;
    }
}
