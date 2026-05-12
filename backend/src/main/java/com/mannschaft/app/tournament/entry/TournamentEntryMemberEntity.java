package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 大会エントリーメンバーエンティティ。
 *
 * <p>大会参加チームに登録された試合出場可能な選手リストを表す。
 * user_id は users テーブルへのクロスドメイン参照のため FK を持たない。</p>
 */
@Entity
@Table(name = "tournament_entry_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentEntryMemberEntity extends UuidV7Entity {

    @Column(nullable = false)
    private Long participantId;

    /** クロスドメイン参照: users.id（FK なし、アプリ層で整合性保証） */
    @Column(nullable = false)
    private Long userId;

    private Integer jerseyNumber;

    @Column(length = 30)
    private String position;

    @Column(length = 200)
    private String notes;

    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = (short) 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
