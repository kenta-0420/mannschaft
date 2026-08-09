package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * ユーザー興味・関心タグリポジトリ（F09.17 AdSegmentEvaluator Phase A）。
 *
 * <p>広告ターゲティングの INTEREST_TAG セグメント評価に使用する。
 * findUserIdsByTagHashIn は AdSegmentEvaluator（advertising ドメイン）から呼び出される
 * クロスドメイン SELECT。FK なし、参照のみ（CLAUDE.md 原則 1 準拠）。</p>
 */
public interface UserInterestTagRepository extends JpaRepository<UserInterestTagEntity, UUID> {

    /**
     * 指定ユーザーのタグ一覧を取得する。
     *
     * @param userId ユーザーID
     * @return タグエンティティリスト
     */
    List<UserInterestTagEntity> findByUserId(Long userId);

    /**
     * 指定ユーザーのタグをすべて削除する（退会時の PII 消去用）。
     *
     * @param userId ユーザーID
     */
    void deleteByUserId(Long userId);

    /**
     * INTEREST_TAG セグメント検索: tag_hash に一致するユーザーIDを返す。
     *
     * <p>F09.17 {@code InterestTagSegmentEvaluator} から呼び出す。
     * {@code DISTINCT} により同一ユーザーが複数タグで重複しても 1 件に絞る。</p>
     *
     * @param tagHashes HMAC-SHA256 ハッシュのリスト
     * @return 一致したユーザーID リスト
     */
    @Query("SELECT DISTINCT t.userId FROM UserInterestTagEntity t WHERE t.tagHash IN :tagHashes")
    List<Long> findUserIdsByTagHashIn(@Param("tagHashes") List<String> tagHashes);

    /**
     * INTEREST_TAG セグメントの件数のみを COUNT クエリ1本で取得する（{@link #findUserIdsByTagHashIn} の件数版）。
     * DISTINCT ユーザー数を数えるため {@code COUNT(DISTINCT ...)} を用いる。
     *
     * @param tagHashes HMAC-SHA256 ハッシュのリスト
     * @return 一致したユーザー数
     */
    @Query("SELECT COUNT(DISTINCT t.userId) FROM UserInterestTagEntity t WHERE t.tagHash IN :tagHashes")
    long countUserIdsByTagHashIn(@Param("tagHashes") List<String> tagHashes);
}
