package com.mannschaft.app.family;

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
 * コイントス結果エンティティ。コイントスの結果履歴を保持する。
 */
@Entity
@Table(name = "coin_toss_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CoinTossResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CoinTossMode mode;

    @Column(nullable = false, columnDefinition = "JSON")
    private String options;

    @Column(nullable = false)
    private Integer resultIndex;

    @Column(length = 200)
    private String question;

    @Column(nullable = false)
    private Boolean sharedToChat;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.sharedToChat == null) {
            this.sharedToChat = false;
        }
    }

    /**
     * チャット共有済みに更新する。
     */
    public void markShared() {
        this.sharedToChat = true;
    }
}
