package com.mannschaft.app.proxyvote.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.proxyvote.VoteType;
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

import java.time.LocalDateTime;

/**
 * 投票回答エンティティ。1ユーザー x 1議案 = 1レコード。
 */
@Entity
@Table(name = "proxy_votes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProxyVoteEntity extends BaseEntity {

    @Column(nullable = false)
    private Long motionId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoteType voteType;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isProxyVote = false;

    private Long delegationId;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime votedAt = LocalDateTime.now();
}
