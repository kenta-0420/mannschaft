package com.mannschaft.app.proxyvote.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.proxyvote.MotionResult;
import com.mannschaft.app.proxyvote.RequiredApproval;
import com.mannschaft.app.proxyvote.VotingStatus;
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
 * 議案エンティティ。1セッションに複数の議案を紐付ける。
 */
@Entity
@Table(name = "proxy_vote_motions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProxyVoteMotionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Integer motionNumber;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private VotingStatus votingStatus = VotingStatus.PENDING;

    private LocalDateTime voteDeadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RequiredApproval requiredApproval = RequiredApproval.MAJORITY;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MotionResult result;

    @Column(nullable = false)
    @Builder.Default
    private Integer approveCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer rejectCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer abstainCount = 0;

    /**
     * 議案のタイトルと説明を更新する。
     */
    public void update(String title, String description, RequiredApproval requiredApproval) {
        this.title = title;
        this.description = description;
        this.requiredApproval = requiredApproval;
    }

    /**
     * OPEN 中の軽微な修正（タイトル・説明のみ）。
     */
    public void updateWhenOpen(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /**
     * 投票状態を変更する。
     */
    public void changeVotingStatus(VotingStatus newStatus) {
        this.votingStatus = newStatus;
    }

    /**
     * 投票タイマーの締切日時を設定する。
     */
    public void setVoteDeadline(LocalDateTime deadline) {
        this.voteDeadlineAt = deadline;
    }

    /**
     * 採決結果を設定する。
     */
    public void setResult(MotionResult result) {
        this.result = result;
    }

    /**
     * 投票カウントを加算する。
     */
    public void incrementVoteCount(com.mannschaft.app.proxyvote.VoteType voteType) {
        switch (voteType) {
            case APPROVE -> this.approveCount++;
            case REJECT -> this.rejectCount++;
            case ABSTAIN -> this.abstainCount++;
        }
    }

    /**
     * 投票カウントを減算する。
     */
    public void decrementVoteCount(com.mannschaft.app.proxyvote.VoteType voteType) {
        switch (voteType) {
            case APPROVE -> { if (this.approveCount > 0) this.approveCount--; }
            case REJECT -> { if (this.rejectCount > 0) this.rejectCount--; }
            case ABSTAIN -> { if (this.abstainCount > 0) this.abstainCount--; }
        }
    }

    /**
     * 投票カウントをリセットする。
     */
    public void resetVoteCounts() {
        this.approveCount = 0;
        this.rejectCount = 0;
        this.abstainCount = 0;
    }
}
