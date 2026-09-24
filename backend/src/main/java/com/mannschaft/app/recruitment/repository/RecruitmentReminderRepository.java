package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集型予約: リマインダーリポジトリ。
 * バッチ処理・confirmApplication で使用する。
 */
public interface RecruitmentReminderRepository extends JpaRepository<RecruitmentReminderEntity, Long> {

    /**
     * 送信すべきリマインダーを最大100件取得する。バッチ処理で使用する。
     *
     * <p><b>「まだ開始していない募集」に限る（{@code l.startAt > :now}）。</b>
     * 初版は {@code sentAt IS NULL AND remindAt <= now} という<b>上限だけ</b>で絞っており、
     * 下限が無かった。そのためバッチが数日走らなかっただけで、再開時に
     * <b>既に開始・終了した募集にまで「24時間後に開催されます」を最大100件/分で送り出す</b>
     * （Codex 検分の指摘。機能フラグとは無関係に、障害で一日止まっただけでも起きる）。</p>
     *
     * <p>下限を「開始時刻がまだ未来であること」に取ったのは、この通知の文面が
     * 「24時間後に開催」であり、<b>開始済みの募集に送る意味が原理的に無い</b>ためである。
     * 任意の猶予時間（何時間前までなら送ってよいか）という恣意的な定数を置かずに済む。</p>
     *
     * <p>募集が既に削除されている行は従来どおり取得する（{@code l.id IS NULL} を許す）。
     * 呼び出し側がそれらを「送らずに消化（sent_at 更新）」して掃除する経路を保つため。</p>
     *
     * @param now      現在日時 (UTC)
     * @param pageable 取得件数（バッチは 100 件で呼ぶ）
     * @return 送信対象リマインダー
     */
    @Query("""
            SELECT r
            FROM RecruitmentReminderEntity r
            LEFT JOIN RecruitmentListingEntity l ON l.id = r.listingId
            WHERE r.sentAt IS NULL
              AND r.remindAt <= :now
              AND (l.id IS NULL OR l.startAt > :now)
            ORDER BY r.remindAt ASC
            """)
    List<RecruitmentReminderEntity> findSendableReminders(
            @Param("now") LocalDateTime now, Pageable pageable);

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
