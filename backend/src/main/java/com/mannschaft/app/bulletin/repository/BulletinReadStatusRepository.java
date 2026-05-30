package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 掲示板既読ステータスリポジトリ。
 */
public interface BulletinReadStatusRepository extends JpaRepository<BulletinReadStatusEntity, Long> {

    /**
     * スレッドとユーザーの既読状態を取得する。
     */
    Optional<BulletinReadStatusEntity> findByThreadIdAndUserId(Long threadId, Long userId);

    /**
     * スレッドとユーザーの既読状態が存在するか確認する。
     */
    boolean existsByThreadIdAndUserId(Long threadId, Long userId);

    /**
     * スレッドの既読ユーザー一覧を取得する。
     */
    List<BulletinReadStatusEntity> findByThreadIdOrderByReadAtDesc(Long threadId);

    /**
     * スレッドの既読数を取得する。
     */
    long countByThreadId(Long threadId);

    /**
     * 指定スレッド集合のうち、当該ユーザーが既読済みのスレッド ID を返す（一括既読の差分抽出用）。
     *
     * <p>F17.1 村掲示板グローバル方式の一括既読で、未読スレッドのみを既読化するために使う
     * （1 クエリで既読集合を取得し、N+1 を避ける）。</p>
     *
     * @param threadIds 対象スレッド ID 集合
     * @param userId    ユーザー ID
     * @return 既読済みスレッド ID のリスト
     */
    @Query("SELECT r.threadId FROM BulletinReadStatusEntity r "
            + "WHERE r.threadId IN :threadIds AND r.userId = :userId")
    List<Long> findReadThreadIds(@Param("threadIds") Collection<Long> threadIds, @Param("userId") Long userId);
}
