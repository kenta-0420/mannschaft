package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.UserMuteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ユーザーミュートリポジトリ。
 */
public interface UserMuteRepository extends JpaRepository<UserMuteEntity, Long> {

    /**
     * ユーザーのミュート一覧を取得する。
     */
    List<UserMuteEntity> findByUserId(Long userId);

    /**
     * ユーザー・ミュート種別・ミュート対象IDでミュートを取得する。
     */
    Optional<UserMuteEntity> findByUserIdAndMutedTypeAndMutedId(Long userId, String mutedType, Long mutedId);

    /**
     * ユーザーがミュート済みかを判定する。
     */
    boolean existsByUserIdAndMutedTypeAndMutedId(Long userId, String mutedType, Long mutedId);

    /**
     * ユーザーのミュート総件数（種別を問わない）。上限（200 件）検証に使う。
     */
    long countByUserId(Long userId);

    /**
     * ミュート対象 ID だけを射影して返す。
     *
     * <p>マイフィードの {@code NOT IN} 条件に渡すための軽量クエリ。Entity を丸ごと取ると
     * 件数分のインスタンス化が無駄になるため ID のみを引く。</p>
     *
     * @param userId    ユーザー ID
     * @param mutedType {@code "TEAM"} または {@code "ORGANIZATION"}
     * @return ミュート対象 ID 一覧（0 件なら空リスト）
     */
    @Query("SELECT m.mutedId FROM UserMuteEntity m WHERE m.userId = :userId AND m.mutedType = :mutedType")
    List<Long> findMutedIdsByUserIdAndMutedType(@Param("userId") Long userId,
                                                @Param("mutedType") String mutedType);
}
