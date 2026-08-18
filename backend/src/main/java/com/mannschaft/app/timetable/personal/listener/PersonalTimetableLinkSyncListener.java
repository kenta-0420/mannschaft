package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.event.TimetableChangeCreatedEvent;
import com.mannschaft.app.timetable.event.TimetableChangeDeletedEvent;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * F03.15 Phase 4: チームリンクされた個人時間割コマに対して、
 * 臨時変更（休講・差替・追加・休日）を個人スケジュール（schedules テーブル）へ
 * 自動反映するリスナー。
 *
 * <p>設計書 §5.2 を参照。</p>
 *
 * <ul>
 *   <li>外部参照キー: {@code external_ref = "F03.15:{change_id}:{slot_id}"} 形式で idempotent 化</li>
 *   <li>有効条件: 個人設定 {@code auto_reflect_class_changes_to_calendar = true}
 *       かつ コマ {@code auto_sync_changes = true} かつ 親個人時間割 {@code status = ACTIVE}</li>
 *   <li>DAY_OFF 優先: 同日重複時は DAY_OFF のみ反映（個別変更は無視）</li>
 *   <li>取消フロー: TimetableChangeDeletedEvent で external_ref に紐付くスケジュールを論理削除</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalTimetableLinkSyncListener {

    private static final String SOURCE_PREFIX = "F03.15";
    private static final String CANCEL_COLOR = "#999999";
    private static final String REPLACE_COLOR = "#F5A623";
    private static final String ADD_COLOR = "#4A90E2";
    private static final List<String> WEEK_DOWS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final PersonalTimetableSlotRepository personalSlotRepository;
    private final PersonalTimetableRepository personalTimetableRepository;
    private final PersonalTimetablePeriodRepository personalPeriodRepository;
    private final PersonalTimetableSettingsRepository settingsRepository;
    private final TimetableChangeRepository timetableChangeRepository;
    private final TimetableSlotRepository timetableSlotRepository;
    private final ScheduleRepository scheduleRepository;
    /** F04.3 通知ヘルパー（テスト容易性のため Optional 注入）。 */
    @Autowired(required = false)
    private NotificationHelper notificationHelper;
    /** Issue #2715 ロットB: 受信者 locale の解決（D-5: auth の UserRepository を直接呼ばない）。 */
    @Autowired(required = false)
    private UserLocaleCache userLocaleCache;
    @Autowired(required = false)
    private MessageSource messageSource;

    /**
     * 臨時変更作成/更新時にリンクされた個人スロットへスケジュールを生成する。
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onChangeCreated(TimetableChangeCreatedEvent event) {
        try {
            TimetableChangeEntity change = timetableChangeRepository.findById(event.getChangeId())
                    .orElse(null);
            if (change == null) {
                log.debug("臨時変更が見つかりません（既に削除済みの可能性）: changeId={}", event.getChangeId());
                return;
            }

            // DAY_OFF 優先ルール: 同日に DAY_OFF があり、かつ自身が個別 CANCEL/REPLACE/ADD の場合スキップ
            if (change.getChangeType() != TimetableChangeType.DAY_OFF) {
                boolean dayOffExists = timetableChangeRepository
                        .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(
                                event.getTimetableId(), event.getTargetDate())
                        .map(c -> c.getChangeType() == TimetableChangeType.DAY_OFF)
                        .orElse(false);
                if (dayOffExists) {
                    log.debug("DAY_OFF が同日に存在するため個別変更を無視: changeId={}, date={}",
                            change.getId(), event.getTargetDate());
                    return;
                }
            }

            List<PersonalTimetableSlotEntity> linkedSlots =
                    personalSlotRepository.findByLinkedTimetableId(event.getTimetableId());
            if (linkedSlots.isEmpty()) {
                return;
            }

            for (PersonalTimetableSlotEntity slot : linkedSlots) {
                processSlotForChange(slot, change, event.getTargetDate());
            }
        } catch (Exception ex) {
            log.error("PersonalTimetableLinkSyncListener.onChangeCreated 失敗: changeId={}, error={}",
                    event.getChangeId(), ex.getMessage(), ex);
        }
    }

    /**
     * 臨時変更削除時に対応する個人スケジュールを論理削除する。
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onChangeDeleted(TimetableChangeDeletedEvent event) {
        try {
            String prefix = SOURCE_PREFIX + ":" + event.getChangeId() + ":%";
            List<ScheduleEntity> targets = scheduleRepository.findByExternalRefPrefix(prefix);
            // N+1 是正（PR #2809 検分二次）: 取消対象ごとに findById するとチーム時間割の
            // リンク数に比例して SQL が増える。externalRef から全 slotId を先に集め、
            // findAllById で一括取得してから subjectName マップを作る。
            Map<Long, String> subjectNamesBySlotId = resolveSubjectNamesByExternalRefs(targets);
            for (ScheduleEntity sch : targets) {
                sch.softDelete();
                scheduleRepository.save(sch);
                if (notificationHelper != null && sch.getUserId() != null) {
                    try {
                        Locale locale = resolveLocale(sch.getUserId());
                        String notifTitle = messageSource != null
                                ? messageSource.getMessage(
                                        "notification.timetable.personalLink.revoked.title", null,
                                        "授業変更が取り消されました", locale)
                                : "授業変更が取り消されました";
                        String notifBody = buildRevokedNotificationBody(sch, locale, subjectNamesBySlotId);
                        notificationHelper.notify(
                                sch.getUserId(),
                                "TIMETABLE_CHANGE_REVOKED",
                                notifTitle,
                                notifBody,
                                "SCHEDULE", sch.getId(),
                                NotificationScopeType.PERSONAL, sch.getUserId(),
                                "/schedules/" + sch.getId(),
                                null);
                    } catch (Exception ex) {
                        log.warn("通知送信失敗（継続）: userId={}, error={}",
                                sch.getUserId(), ex.getMessage());
                    }
                }
            }
            log.info("臨時変更削除に伴う個人スケジュール削除: changeId={}, count={}",
                    event.getChangeId(), targets.size());
        } catch (Exception ex) {
            log.error("PersonalTimetableLinkSyncListener.onChangeDeleted 失敗: changeId={}, error={}",
                    event.getChangeId(), ex.getMessage(), ex);
        }
    }

    private void processSlotForChange(PersonalTimetableSlotEntity slot,
                                       TimetableChangeEntity change,
                                       LocalDate targetDate) {
        // ユーザー設定 / コマ設定 / 親 ACTIVE の三段ガード
        if (!Boolean.TRUE.equals(slot.getAutoSyncChanges())) {
            return;
        }
        PersonalTimetableEntity personal = personalTimetableRepository
                .findById(slot.getPersonalTimetableId()).orElse(null);
        if (personal == null || personal.getDeletedAt() != null
                || personal.getStatus() != PersonalTimetableStatus.ACTIVE) {
            return;
        }
        Optional<PersonalTimetableSettingsEntity> settingsOpt =
                settingsRepository.findById(personal.getUserId());
        boolean autoReflect = settingsOpt
                .map(PersonalTimetableSettingsEntity::getAutoReflectClassChangesToCalendar)
                .orElse(true);
        if (!autoReflect) {
            return;
        }

        // 適用期間チェック
        if (personal.getEffectiveFrom() != null && targetDate.isBefore(personal.getEffectiveFrom())) {
            return;
        }
        if (personal.getEffectiveUntil() != null && targetDate.isAfter(personal.getEffectiveUntil())) {
            return;
        }

        // 曜日チェック（個人コマの dayOfWeek と targetDate の曜日が一致する必要がある）
        String targetDow = WEEK_DOWS.get(targetDate.getDayOfWeek().getValue() - 1);
        if (!targetDow.equals(slot.getDayOfWeek())) {
            return;
        }

        // DAY_OFF 以外は period_number 一致チェック
        if (change.getChangeType() != TimetableChangeType.DAY_OFF
                && change.getPeriodNumber() != null
                && !change.getPeriodNumber().equals(slot.getPeriodNumber())) {
            return;
        }

        // 時限定義から開始/終了時刻を取得
        Optional<PersonalTimetablePeriodEntity> periodOpt = personalPeriodRepository
                .findByPersonalTimetableIdOrderByPeriodNumberAsc(personal.getId())
                .stream()
                .filter(p -> p.getPeriodNumber().equals(slot.getPeriodNumber()))
                .findFirst();

        LocalDateTime startAt = periodOpt
                .map(p -> targetDate.atTime(p.getStartTime()))
                .orElse(targetDate.atStartOfDay());
        LocalDateTime endAt = periodOpt
                .map(p -> targetDate.atTime(p.getEndTime()))
                .orElse(targetDate.atTime(23, 59, 0));

        // タイトル・色・description を生成
        String title;
        String color;
        String description;
        switch (change.getChangeType()) {
            case CANCEL -> {
                title = "[休講] " + slot.getSubjectName();
                color = CANCEL_COLOR;
                description = change.getReason();
            }
            case DAY_OFF -> {
                title = "[休講] " + slot.getSubjectName();
                color = CANCEL_COLOR;
                description = change.getReason() != null ? change.getReason() : "終日休講";
            }
            case REPLACE -> {
                title = "[変更] " + slot.getSubjectName()
                        + (change.getSubjectName() != null ? " → " + change.getSubjectName() : "");
                color = REPLACE_COLOR;
                StringBuilder desc = new StringBuilder();
                if (change.getRoomName() != null) desc.append("教室: ").append(change.getRoomName()).append("\n");
                if (change.getTeacherName() != null) desc.append("教員: ").append(change.getTeacherName()).append("\n");
                if (change.getReason() != null) desc.append(change.getReason());
                description = desc.toString();
            }
            case ADD -> {
                title = "[補講] "
                        + (change.getSubjectName() != null ? change.getSubjectName() : slot.getSubjectName());
                color = ADD_COLOR;
                description = change.getReason();
            }
            default -> {
                return;
            }
        }

        String externalRef = SOURCE_PREFIX + ":" + change.getId() + ":" + slot.getId();
        Optional<ScheduleEntity> existing = scheduleRepository.findByExternalRef(externalRef);
        ScheduleEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.updateScheduleFields(title, description, slot.getRoomName(), startAt, endAt, color);
        } else {
            entity = ScheduleEntity.builder()
                    .userId(personal.getUserId())
                    .title(title)
                    .description(description)
                    .location(slot.getRoomName())
                    .startAt(startAt)
                    .endAt(endAt)
                    .allDay(change.getChangeType() == TimetableChangeType.DAY_OFF)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .minResponseRole(MinResponseRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .color(color)
                    .externalRef(externalRef)
                    .createdBy(personal.getUserId())
                    .build();
        }
        scheduleRepository.save(entity);

        // F04.3 通知（受信者 locale に従って件名を組み立てる。Issue #2715 ロットB）
        if (notificationHelper != null) {
            try {
                Locale locale = resolveLocale(personal.getUserId());
                String notifTitle = buildSyncedNotificationTitle(change, slot, locale);
                String notifBody = messageSource != null
                        ? messageSource.getMessage(
                                "notification.timetable.personalLink.synced.body",
                                new Object[]{slot.getSubjectName(), targetDate.toString()},
                                slot.getSubjectName() + "（" + targetDate + "）", locale)
                        : slot.getSubjectName() + "（" + targetDate + "）";
                notificationHelper.notify(
                        personal.getUserId(),
                        "TIMETABLE_CHANGE_SYNCED",
                        notifTitle,
                        notifBody,
                        "SCHEDULE", entity.getId(),
                        NotificationScopeType.PERSONAL, personal.getUserId(),
                        "/schedules/" + (entity.getId() == null ? "" : entity.getId()),
                        null);
            } catch (Exception ex) {
                log.warn("通知送信失敗（継続）: userId={}, error={}",
                        personal.getUserId(), ex.getMessage());
            }
        }
    }

    /**
     * 通知の件名（{@code notifTitle}）を組み立てる。
     *
     * <p>スケジュール保存用の {@code title}（{@code processSlotForChange} 冒頭の switch で組み立てる、
     * DB 保存対象の日本語文字列）とは別に、<b>通知の件名だけ</b>を受信者 locale で組み立てる
     * （Issue #2715: i18n 対象は通知の件名・本文のみ。スケジュール本体の title は対象外）。</p>
     */
    private String buildSyncedNotificationTitle(TimetableChangeEntity change,
                                                 PersonalTimetableSlotEntity slot, Locale locale) {
        if (messageSource == null) {
            return switch (change.getChangeType()) {
                case CANCEL, DAY_OFF -> "[休講] " + slot.getSubjectName();
                case REPLACE -> "[変更] " + slot.getSubjectName()
                        + (change.getSubjectName() != null ? " → " + change.getSubjectName() : "");
                case ADD -> "[補講] " + (change.getSubjectName() != null ? change.getSubjectName() : slot.getSubjectName());
            };
        }
        return switch (change.getChangeType()) {
            case CANCEL, DAY_OFF -> messageSource.getMessage(
                    "notification.timetable.personalLink.synced.title.cancel",
                    new Object[]{slot.getSubjectName()}, "[休講] " + slot.getSubjectName(), locale);
            case REPLACE -> {
                if (change.getSubjectName() != null) {
                    yield messageSource.getMessage(
                            "notification.timetable.personalLink.synced.title.replaceWithSubject",
                            new Object[]{slot.getSubjectName(), change.getSubjectName()},
                            "[変更] " + slot.getSubjectName() + " → " + change.getSubjectName(), locale);
                }
                yield messageSource.getMessage(
                        "notification.timetable.personalLink.synced.title.replace",
                        new Object[]{slot.getSubjectName()}, "[変更] " + slot.getSubjectName(), locale);
            }
            case ADD -> {
                String subject = change.getSubjectName() != null ? change.getSubjectName() : slot.getSubjectName();
                yield messageSource.getMessage(
                        "notification.timetable.personalLink.synced.title.add",
                        new Object[]{subject}, "[補講] " + subject, locale);
            }
        };
    }

    /**
     * 取消通知の本文（{@code notifBody}）を組み立てる。
     *
     * <p>{@code sch.getTitle()} は {@code "[休講] Math"} のような合成済み日本語文字列であり、
     * 文字列分解でプレフィックスを剥がすのは禁止（構造化データではないため）。
     * かわりに {@code externalRef}（{@code "F03.15:{changeId}:{slotId}"}）から復元した slotId を
     * キーに、あらかじめ一括取得しておいた {@code subjectNamesBySlotId}（{@link #resolveSubjectNamesByExternalRefs}）
     * を引く（synced 側の {@code notification.timetable.personalLink.synced.body} と同じ
     * {@code {0}}=科目名, {@code {1}}=日付 の作り）。</p>
     */
    private String buildRevokedNotificationBody(ScheduleEntity sch, Locale locale,
                                                  Map<Long, String> subjectNamesBySlotId) {
        Long slotId = parseSlotIdFromExternalRef(sch.getExternalRef());
        String subjectName = slotId != null ? subjectNamesBySlotId.get(slotId) : null;
        String dateText = sch.getStartAt() != null ? sch.getStartAt().toLocalDate().toString() : "";
        if (subjectName == null) {
            // 科目名が復元できない場合はスケジュールの title をそのまま使う（フォールバック）。
            return sch.getTitle();
        }
        return messageSource != null
                ? messageSource.getMessage(
                        "notification.timetable.personalLink.revoked.body",
                        new Object[]{subjectName, dateText},
                        subjectName + "（" + dateText + "）", locale)
                : subjectName + "（" + dateText + "）";
    }

    /**
     * 取消対象の {@link ScheduleEntity} 群から {@code externalRef} 経由で slotId を集め、
     * {@link PersonalTimetableSlotRepository#findAllById} で一括取得して
     * {@code slotId -> subjectName} のマップを組み立てる（PR #2809 検分二次: N+1 是正）。
     *
     * <p>取消対象1件ごとに {@code findById} していた旧実装は、チーム時間割にリンクする
     * 個人コマ数（＝取消対象数）に比例して SQL 発行数が増えるため、書き込みトランザクション内の
     * 遅延・ロック保持時間が伸びる問題があった。</p>
     */
    private Map<Long, String> resolveSubjectNamesByExternalRefs(List<ScheduleEntity> targets) {
        List<Long> slotIds = targets.stream()
                .map(sch -> parseSlotIdFromExternalRef(sch.getExternalRef()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (slotIds.isEmpty()) {
            return Map.of();
        }
        return personalSlotRepository.findAllById(slotIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        PersonalTimetableSlotEntity::getId,
                        PersonalTimetableSlotEntity::getSubjectName));
    }

    /**
     * {@code externalRef}（{@code "F03.15:{changeId}:{slotId}"}）から個人時間割コマの
     * slotId を復元する。復元できない場合は {@code null} を返す。
     */
    private Long parseSlotIdFromExternalRef(String externalRef) {
        if (externalRef == null) {
            return null;
        }
        int lastColon = externalRef.lastIndexOf(':');
        if (lastColon < 0 || lastColon == externalRef.length() - 1) {
            return null;
        }
        try {
            return Long.valueOf(externalRef.substring(lastColon + 1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 受信者ユーザーの locale を解決する（{@link UserLocaleCache} 経由。D-5: auth の
     * {@code UserRepository} を直接呼ばない）。
     */
    private Locale resolveLocale(Long userId) {
        if (userLocaleCache == null || userId == null) {
            return Locale.forLanguageTag("ja");
        }
        return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
    }

    /**
     * targetDate の曜日と一致しない slot を弾くためのヘルパー（テストから利用）。
     */
    public static String dayOfWeekToShortName(DayOfWeek dow) {
        return WEEK_DOWS.get(dow.getValue() - 1);
    }
}
