package com.mannschaft.app.search.entity;

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

import java.time.LocalDateTime;

/**
 * 検索履歴エンティティ。ユーザーの検索キーワード履歴を管理する。
 */
@Entity
@Table(name = "search_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SearchHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String query;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime searchedAt = LocalDateTime.now();

    /**
     * 検索日時を現在時刻に更新する（同一クエリ再検索時）。
     */
    public void refreshSearchedAt() {
        this.searchedAt = LocalDateTime.now();
    }
}
