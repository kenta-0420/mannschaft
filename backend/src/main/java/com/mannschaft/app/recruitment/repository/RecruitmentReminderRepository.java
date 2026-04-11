package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集型予約: リマインダーリポジトリ。
 * バッチ処理・confirmApplication で使用する。
 */
public interface RecruitmentReminderRepository extends JpaRepository<RecruitmentReminderEntity, Long> {

    /**
     * 未送信かつ送信予定時刻を過ぎたリマインダーを最大100件取得する。
     * バッチ処理で使用する。
     *
     * @param now 現在日時 (UTC)
     * @return 送信対象リマインダー (最大100件)
     */
    List<RecruitmentReminderEntity> findTop100BySentAtIsNullAndRemindAtLessThanEqual(LocalDateTime now);

    /**
     * 参加者IDに紐づくリマインダーをすべて取得する。
     *
     * @param participantId 参加者ID
     * @return リマインダーリスト
     */
    List<RecruitmentReminderEntity> findByParticipantId(Long participantId);

    /**
     * 募集IDに紐づくリマインダーをすべて削除する。
     *
     * @param listingId 募集ID
     */
    void deleteByListingId(Long listingId);
}
