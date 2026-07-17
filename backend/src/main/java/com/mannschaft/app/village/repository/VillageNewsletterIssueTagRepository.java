package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterIssueTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 号×タグ中間テーブル リポジトリ（F17.1 ②-1・案Y の pull 層）。
 */
public interface VillageNewsletterIssueTagRepository
        extends JpaRepository<VillageNewsletterIssueTagEntity, UUID> {

    /** ある号に付いたタグ紐付け一覧。 */
    List<VillageNewsletterIssueTagEntity> findByIssueId(UUID issueId);

    /** あるタグが付いた号紐付け一覧（公開一覧のタグ絞り込み・逆引き）。 */
    List<VillageNewsletterIssueTagEntity> findByTagId(UUID tagId);

    /** ある号の全タグ紐付けを削除（タグ付け更新時の入れ替え）。 */
    void deleteByIssueId(UUID issueId);
}
