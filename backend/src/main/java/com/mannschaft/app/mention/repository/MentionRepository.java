package com.mannschaft.app.mention.repository;

import com.mannschaft.app.mention.entity.MentionEntity;
import org.springframework.data.domain.Pageable;
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

    /**
     * 指定ユーザー宛のメンションを作成日時降順で<b>ページング取得</b>する（F04.11 統合インボックス用）。
     *
     * <p>統合インボックスの境界付きウィンドウページング（Phase3 ③）で、無制限 fetch を避けて
     * 上位 window 件のみ取得するために使用する（設計書 03_business_logic.md §4）。
     * 既存の {@link #findByMentionedUserIdOrderByCreatedAtDesc(Long)} は他用途で温存する。</p>
     *
     * @param mentionedUserId メンションされたユーザーの ID
     * @param pageable        取得上限（{@code PageRequest.of(0, window)}）
     * @return メンション一覧（新しい順・最大 window 件）
     */
    List<MentionEntity> findByMentionedUserIdOrderByCreatedAtDesc(Long mentionedUserId, Pageable pageable);
}
