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
     * 指定イベントIDリストの中で指定した回答値（ATTENDING 等）の RSVP 回答一覧を取得する。
     *
     * <p>F03.12 Phase8 バッチが遅刻連絡の有無にかかわらず全 ATTENDING を一括取得するために使用する。
     * メモリ上でオフセット計算を行うことで N+1 を防止する。</p>
     *
     * @param eventIds イベントIDリスト
     * @param response 回答値（"ATTENDING" を渡すこと）
     * @return 該当するRSVP回答リスト
     */
    List<EventRsvpResponseEntity> findByEventIdInAndResponse(List<Long> eventIds, String response);

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

    /**
     * 指定イベントの事前通知一覧（遅刻連絡または欠席連絡が設定されているもの）を取得する。
     *
     * <p>F03.12 Phase8 §15 事前遅刻・欠席連絡の一覧取得に使用する。
     * expectedArrivalMinutesLate が NULL でない、または advanceAbsenceReason が NULL でない
     * レコードを返す。</p>
     *
     * @param eventId イベントID
     * @return 事前通知があるRSVP回答リスト
     */
    List<EventRsvpResponseEntity> findByEventIdAndExpectedArrivalMinutesLateIsNotNullOrEventIdAndAdvanceAbsenceReasonIsNotNull(
            Long eventId1, Long eventId2);

    /**
     * 指定イベントの指定回答値を持つ RSVP 参加者のユーザーIDリストを取得する。
     *
     * <p>F03.12 §16 解散通知サービスが ATTENDING 参加者一覧取得に使用する。</p>
     *
     * @param eventId  イベントID
     * @param response 回答値（"ATTENDING" を渡すこと）
     * @return ユーザーIDリスト
     */
    @Query("SELECT r.userId FROM EventRsvpResponseEntity r WHERE r.eventId = :eventId AND r.response = :response")
    List<Long> findUserIdsByEventIdAndResponse(
            @Param("eventId") Long eventId,
            @Param("response") String response);
}
