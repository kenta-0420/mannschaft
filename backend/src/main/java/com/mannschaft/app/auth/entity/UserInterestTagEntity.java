package com.mannschaft.app.auth.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ユーザーの興味・関心タグエンティティ（F09.17 AdSegmentEvaluator Phase A）。
 *
 * <p>広告ターゲティングの INTEREST_TAG セグメント評価に使用する。
 * タグ文字列は平文で保存し、tag_hash（HMAC-SHA256）で検索する。
 * タグ自体はセンシティブな個人情報ではなく「スポーツ好き」「料理好き」程度のカテゴリ情報のため
 * 暗号化は行わない。ただし tag_hash によるブラインドインデックスで直接クエリ可能にする。</p>
 *
 * <p>主キーは UUIDv7（CLAUDE.md 原則 6 準拠）。</p>
 */
@Entity
@Table(name = "user_interest_tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInterestTagEntity extends UuidV7Entity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tag", nullable = false, length = 50)
    private String tag;

    @Column(name = "tag_hash", nullable = false, length = 64)
    private String tagHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * ファクトリメソッド。
     *
     * @param userId ユーザーID
     * @param tag    タグ文字列（小文字英数字・アンダースコア）
     * @param tagHash tag の HMAC-SHA256
     * @return 新規 UserInterestTagEntity
     */
    public static UserInterestTagEntity create(Long userId, String tag, String tagHash) {
        var entity = new UserInterestTagEntity();
        entity.userId = userId;
        entity.tag = tag;
        entity.tagHash = tagHash;
        return entity;
    }
}
