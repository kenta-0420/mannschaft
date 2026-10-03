package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.entity.VillageCalendarEventEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.repository.VillageCalendarEventRepository;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.service.VillageEventFeedRefluxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * F17.2 Wave2 ① 行事接近通知バッチ（設計書 §3.5）。
 *
 * <p>毎日 09:00 JST に「翌日開催」の行事を走査し、村タイムラインへ EVENT_UPCOMING の
 * システム投稿を1回だけ作り、村人へ通知する。対象は:</p>
 * <ul>
 *   <li>歳時記（{@link VillageCalendarEventEntity}）: 翌日の月日に該当（毎年繰返/単発）</li>
 *   <li>お祭り（{@link VillageFestivalEntity}）: SCHEDULED かつ {@code starts_at} が翌日</li>
 *   <li>寄合（{@link VillageMeetupEntity}）: CONFIRMED かつ {@code confirmed_date} が翌日</li>
 * </ul>
 *
 * <p>前日1回のみ（複数回送らない）。二重送信防止は還流サービスの冪等判定
 * （{@code (scope_village_id, system_post_type, source_event_uuid)} 存在チェック）に委ねる。
 * {@link BatchEndpoint} を付与し {@code POST /api/v1/system-admin/batch/{name}/trigger} から手動キック可能。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageEventUpcomingNoticeBatchService {

    private final VillageFestivalRepository festivalRepository;
    private final VillageMeetupRepository meetupRepository;
    private final VillageCalendarEventRepository calendarEventRepository;
    private final VillageEventFeedRefluxService refluxService;

    /**
     * 翌日開催の行事に接近通知（EVENT_UPCOMING）を1回だけ発火する。
     *
     * <p>cron {@code "0 0 9 * * *"} は毎日 09:00 JST に発火。</p>
     */
    @BatchEndpoint(name = "village-event-upcoming-notice",
            description = "翌日開催の歳時記/祭/寄合(CONFIRMED)を走査し EVENT_UPCOMING を前日1回だけ投稿・通知する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村の行事の開催前告知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "villageEventUpcomingNoticeBatch",
            lockAtLeastFor = "PT30S",
            lockAtMostFor = "PT10M")
    public void runBatch() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        log.info("行事接近通知バッチ開始: tomorrow={}", tomorrow);

        int notified = 0;

        // 祭: SCHEDULED かつ starts_at が翌日（半開区間 [tomorrow 00:00, day-after 00:00)）
        LocalDateTime from = tomorrow.atStartOfDay();
        LocalDateTime to = tomorrow.plusDays(1).atStartOfDay();
        List<VillageFestivalEntity> festivals =
                festivalRepository.findByStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanAndDeletedAtIsNull(
                        VillageFestivalStatus.SCHEDULED, from, to);
        for (VillageFestivalEntity f : festivals) {
            refluxService.publish(f.getVillageId(), VillageEventNotificationType.EVENT_UPCOMING, f.getId(),
                    f.getTitle(), "/villages/" + f.getVillageId() + "/festivals/" + f.getId());
            notified++;
        }

        // 寄合: CONFIRMED かつ confirmed_date が翌日
        List<VillageMeetupEntity> meetups =
                meetupRepository.findByStatusAndConfirmedDateAndDeletedAtIsNull(
                        VillageMeetupStatus.CONFIRMED, tomorrow);
        for (VillageMeetupEntity m : meetups) {
            refluxService.publish(m.getVillageId(), VillageEventNotificationType.EVENT_UPCOMING, m.getId(),
                    m.getTitle(), "/villages/" + m.getVillageId() + "/meetups/" + m.getId());
            notified++;
        }

        // 歳時記: 翌日の月日に該当（毎年繰返/単発）
        List<VillageCalendarEventEntity> events =
                calendarEventRepository.findOccurringOn(
                        tomorrow, tomorrow.getMonthValue(), tomorrow.getDayOfMonth());
        for (VillageCalendarEventEntity e : events) {
            refluxService.publish(e.getVillageId(), VillageEventNotificationType.EVENT_UPCOMING, e.getId(),
                    e.getTitle(), "/villages/" + e.getVillageId() + "/calendar/" + e.getId());
            notified++;
        }

        log.info("行事接近通知バッチ完了: 通知対象={}", notified);
    }
}
