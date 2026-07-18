package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterIssueTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 号×タグ中間テーブル リポジトリ（F17.1 ②-1・案Y の pull 層）。
 */
public interface VillageNewsletterIssueTagRepository
        extends JpaRepository<VillageNewsletterIssueTagEntity, UUID> {

    /** ある号に付いたタグ紐付け一覧。 */
    List<VillageNewsletterIssueTagEntity> findByIssueId(UUID issueId);

    /**
     * 複数号のタグ紐付けを一括取得する（一覧の N+1 回避・②-4 堅牢性 AC-9）。
     *
     * <p>一覧ページに載る全号 ID をまとめて渡し、リンクを 1 クエリで引く。号↔タグの対応は
     * {@code issueId} で厳密に保つ（Service 側で {@code Map<issueId, List<tag>>} に組み立てる）。
     * 空集合は呼び出し側で短絡し、{@code IN ()} を発行しない。</p>
     */
    List<VillageNewsletterIssueTagEntity> findByIssueIdIn(Collection<UUID> issueIds);

    /** あるタグが付いた号紐付け一覧（公開一覧のタグ絞り込み・逆引き）。 */
    List<VillageNewsletterIssueTagEntity> findByTagId(UUID tagId);

    /** ある号の全タグ紐付けを削除（タグ付け更新時の入れ替え）。 */
    void deleteByIssueId(UUID issueId);

    /** あるタグの使用件数（タグ削除時の使用中ガード・②-4）。 */
    long countByTagId(UUID tagId);
}
