package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
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
     * INTEREST_TAG セグメント検索: tag_hash に一致するアクティブユーザーIDを返す。
     *
     * <p>F09.17 {@code InterestTagSegmentEvaluator} から呼び出す。
     * {@code DISTINCT} により同一ユーザーが複数タグで重複しても 1 件に絞る。</p>
     *
     * <p><b>users テーブルとの結合理由</b>: {@code user_interest_tags} 単体には削除・ステータス情報が
     * 存在しないため、これのみを見ると退会（論理削除）済み・停止中のユーザーまで広告配信対象に含めて
     * しまう（他 5 セグメント種別と絞り込み条件が不揃いだった不整合の是正）。{@link UserEntity} と
     * {@code t.userId = u.id} で結合し、{@code deletedAt IS NULL} かつ {@code status = ACTIVE} の
     * ユーザーのみを返す。FK は張らず SELECT のみのクロスドメイン参照（CLAUDE.md 原則 1 準拠）。</p>
     *
     * @param tagHashes HMAC-SHA256 ハッシュのリスト
     * @return 一致したアクティブユーザーID リスト
     */
    @Query("SELECT DISTINCT t.userId FROM UserInterestTagEntity t, UserEntity u "
            + "WHERE t.tagHash IN :tagHashes AND t.userId = u.id "
            + "AND u.deletedAt IS NULL AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE")
    List<Long> findUserIdsByTagHashIn(@Param("tagHashes") List<String> tagHashes);

    /**
     * INTEREST_TAG セグメントの件数のみを COUNT クエリ1本で取得する（{@link #findUserIdsByTagHashIn} の件数版）。
     * DISTINCT ユーザー数を数えるため {@code COUNT(DISTINCT ...)} を用いる。絞り込み条件は
     * {@link #findUserIdsByTagHashIn} と完全に一致させること（推定リーチ数と実配信数の食い違い防止）。
     *
     * @param tagHashes HMAC-SHA256 ハッシュのリスト
     * @return 一致したアクティブユーザー数
     */
    @Query("SELECT COUNT(DISTINCT t.userId) FROM UserInterestTagEntity t, UserEntity u "
            + "WHERE t.tagHash IN :tagHashes AND t.userId = u.id "
            + "AND u.deletedAt IS NULL AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE")
    long countUserIdsByTagHashIn(@Param("tagHashes") List<String> tagHashes);
}
