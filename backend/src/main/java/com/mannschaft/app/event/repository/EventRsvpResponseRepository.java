package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * RSVP回答リポジトリ。
 */
public interface EventRsvpResponseRepository extends JpaRepository<EventRsvpResponseEntity, Long> {

    /**
     * イベントIDでRSVP回答一覧を取得する。
     *
     * @param eventId イベントID
     * @return RSVP回答リスト
     */
    List<EventRsvpResponseEntity> findByEventId(Long eventId);

    /**
     * イベントID・ユーザーIDでRSVP回答を取得する。
     *
     * @param eventId イベントID
     * @param userId  ユーザーID
     * @return RSVP回答
     */
    Optional<EventRsvpResponseEntity> findByEventIdAndUserId(Long eventId, Long userId);

    /**
     * イベントID・回答値で件数を取得する。
     *
     * @param eventId  イベントID
     * @param response 回答値
     * @return 件数
     */
    long countByEventIdAndResponse(Long eventId, String response);

    /**
     * 指定イベントIDリストの中で ATTENDING かつ遅刻連絡なし（expectedArrivalMinutesLate が null）の
     * RSVP回答一覧を取得する。
     *
     * <p>F03.12 Phase4 バッチが不在アラート対象者の絞り込みに使用する。</p>
     *
     * @param eventIds イベントIDリスト
     * @param response 回答値（"ATTENDING" を渡すこと）
     * @return 該当するRSVP回答リスト
     */
    List<EventRsvpResponseEntity> findByEventIdInAndResponseAndExpectedArrivalMinutesLateIsNull(
            List<Long> eventIds, String response);

    /**
     * 点呼候補者取得：ATTENDING または MAYBE の RSVP 回答一覧を取得する。
     *
     * <p>F03.12 §14 主催者点呼機能。点呼画面に表示する参加予定者を絞り込む。</p>
     *
     * @param eventId イベントID
     * @return ATTENDING / MAYBE の RSVP 回答リスト
     */
    @Query("SELECT r FROM EventRsvpResponseEntity r WHERE r.eventId = :eventId AND r.response IN ('ATTENDING', 'MAYBE')")
    List<EventRsvpResponseEntity> findAttendingOrMaybeByEventId(@Param("eventId") Long eventId);
}
