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
}
