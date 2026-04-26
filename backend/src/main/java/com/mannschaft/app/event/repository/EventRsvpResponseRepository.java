package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * 指定イベントの指定回答値を持つ RSVP 参加者のユーザーIDリストを取得する。
     *
     * <p>F03.12 §16 解散通知サービスが ATTENDING 参加者一覧取得に使用する。</p>
     *
     * @param eventId  イベントID
     * @param response 回答値（"ATTENDING" を渡すこと）
     * @return ユーザーIDリスト
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT r.userId FROM EventRsvpResponseEntity r WHERE r.eventId = :eventId AND r.response = :response")
    List<Long> findUserIdsByEventIdAndResponse(
            @org.springframework.data.repository.query.Param("eventId") Long eventId,
            @org.springframework.data.repository.query.Param("response") String response);
}
