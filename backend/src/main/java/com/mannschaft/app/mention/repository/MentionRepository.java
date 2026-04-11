package com.mannschaft.app.mention.repository;

import com.mannschaft.app.mention.entity.MentionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * メンションリポジトリ。
 */
public interface MentionRepository extends JpaRepository<MentionEntity, Long> {

    /**
     * 指定ユーザー宛のメンションを作成日時降順で取得する。
     *
     * @param mentionedUserId メンションされたユーザーの ID
     * @return メンション一覧（新しい順）
     */
    List<MentionEntity> findByMentionedUserIdOrderByCreatedAtDesc(Long mentionedUserId);
}
