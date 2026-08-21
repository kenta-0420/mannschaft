package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.event.TimetableChangeCreatedEvent;
import com.mannschaft.app.timetable.event.TimetableChangeDeletedEvent;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.event.PersonalTimetableSyncNotificationEvent;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * F03.15 Phase 4: チームリンクされた個人時間割コマに対して、
 * 臨時変更（休講・差替・追加・休日）を個人スケジュール（schedules テーブル）へ
 * 自動反映するリスナー（第1段）。
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
 *
 * <h2>二段構え（Issue #2834 / CMP-056 型確立PR）</h2>
 * <p>本クラス（第1段）は {@link TimetableChangeCreatedEvent} / {@link TimetableChangeDeletedEvent}
 * を {@code AFTER_COMMIT + REQUIRES_NEW + @Async} で受信し、{@code schedules} テーブルの
 * save / softDelete のみを行う。通知の生成・配信は {@code createNotification} を直接呼ばず、
 * 反映処理の結果を {@link PersonalTimetableSyncNotificationEvent} として publish するだけに留める。
 * これは自身の {@code REQUIRES_NEW} トランザクションの<b>内側</b>で publish されるため、
 * このトランザクションが commit された後（第2段）に
 * {@code PersonalTimetableSyncNotificationListener} が非同期で受け取り、受信者ごとに
 * {@code NotificationDeliveryRunner}（{@code REQUIRES_NEW}）を 1 件ずつ呼ぶ。</p>
 *
 * <p><b>是正前の欠陥</b>: 旧実装は素の {@code @EventListener}（{@code AFTER_COMMIT} ではない）で
 * {@code REQUIRES_NEW} トランザクションを開始していたため、元の時間割変更トランザクションが
 * まだコミットしていない段階で個人スケジュールの反映が確定してしまい、元トランザクションが
 * 後でロールバックしても個人スケジュールだけ残る不整合を持っていた。{@code AFTER_COMMIT} 化に
 * よりこの不整合も同時に解消する。</p>
 *
 * <h2>取消通知の source（AC-5・実測確認済み）</h2>
 * <p>取消通知は元々 {@code sourceType=SCHEDULE} + 削除済み {@code scheduleId} を参照していたが、
 * {@code AFTER_COMMIT} 化後は通知発火時点で当該行が既に soft-delete 済みのため、
 * {@code ScheduleVisibilityResolver} の status ガード（{@code DELETED → 誰も不可視}）に必ず deny
 * される。<b>候補として検討した {@code sourceType=PERSONAL_TIMETABLE} は、
 * {@code NotificationSourceTypeMapper} には登録済みだが {@code ContentVisibilityChecker} に対応する
 * Resolver が未実装（{@code ContentVisibilityChecker} 起動ログで resolver 一覧を実測確認：
 * 7 Resolver のいずれも {@code PERSONAL_TIMETABLE} を持たない）</b>ため、これを使うと
 * {@code decide()} が常に {@code UNSPECIFIED → UNSUPPORTED_REFERENCE_TYPE} で fail-closed deny となり、
 * 削除済みかどうかに関わらず<b>全通知が恒久的に消える</b>（旧実装より悪化する）。
 * 本 PR のスコープは通知トランザクション分離の型確立であり Resolver 新設は含まないため、
 * 取消通知の {@code sourceType} は {@code NotificationSourceTypeMapper} が扱わない
 * {@code "PERSONAL_TIMETABLE_SYNC_REVOKED"}（未マッピング＝ visibility ガード対象外の pass-through）
 * とし、{@code sourceId} には生存中の {@code personalTimetableId} を用いる。受信者は常に当該個人
 * 時間割の所有者本人（他者への漏洩経路が無い自己通知）であるため、ガード対象外としても安全側に
 * 倒れる。遷移先も削除済み予定詳細ではなく個人時間割詳細（{@code /me/personal-timetable/{id}}）
 * とする。将来 {@code PERSONAL_TIMETABLE} 用 Resolver が実装された時点で、この sourceType を
 * {@code PERSONAL_TIMETABLE} + Resolver 経由へ置き換えること。</p>
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

    /** AC-5: 取消通知は削除済み SCHEDULE ではなく生存中の personalTimetableId を参照する。 */
    private static final String REVOKED_SOURCE_TYPE = "PERSONAL_TIMETABLE_SYNC_REVOKED";
    private static final String SYNCED_NOTIFICATION_TYPE = "TIMETABLE_CHANGE_SYNCED";
    private static final String REVOKED_NOTIFICATION_TYPE = "TIMETABLE_CHANGE_REVOKED";

    private final PersonalTimetableSlotRepository personalSlotRepository;
    private final PersonalTimetableRepository personalTimetableRepository;
    private final PersonalTimetablePeriodRepository personalPeriodRepository;
    private final PersonalTimetableSettingsRepository settingsRepository;
    private final TimetableChangeRepository timetableChangeRepository;
    private final TimetableSlotRepository timetableSlotRepository;
    private final ScheduleRepository scheduleRepository;
    /** Issue #2834 / CMP-056: 通知は業務コミット後に発火する（業務サービスから Runner を直接呼ばない）。 */
    private final ApplicationEventPublisher eventPublisher;
    /** Issue #2715 ロットB: 受信者 locale の解決（D-5: auth の UserRepository を直接呼ばない）。 */
    @Autowired(required = false)
    private UserLocaleCache userLocaleCache;
    @Autowired(required = false)
    private MessageSource messageSource;

    /**
     * 臨時変更作成/更新時にリンクされた個人スロットへスケジュールを生成する（第1段）。
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onChangeCreated(TimetableChangeCreatedEvent event) {
        List<NotificationDeliveryRequest> notificationRequests = new ArrayList<>();
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
                NotificationDeliveryRequest request = processSlotForChange(slot, change, event.getTargetDate());
                if (request != null) {
                    notificationRequests.add(request);
                }
            }
        } catch (Exception ex) {
            log.error("PersonalTimetableLinkSyncListener.onChangeCreated 失敗: changeId={}, error={}",
                    event.getChangeId(), ex.getMessage(), ex);
        } finally {
            publishNotifications(notificationRequests);
        }
    }

    /**
     * 臨時変更削除時に対応する個人スケジュールを論理削除する（第1段）。
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onChangeDeleted(TimetableChangeDeletedEvent event) {
        List<NotificationDeliveryRequest> notificationRequests = new ArrayList<>();
        try {
            String prefix = SOURCE_PREFIX + ":" + event.getChangeId() + ":%";
            List<ScheduleEntity> targets = scheduleRepository.findByExternalRefPrefix(prefix);
            // N+1 是正（PR #2809 検分二次）: 取消対象ごとに findById するとチーム時間割の
            // リンク数に比例して SQL が増える。externalRef から全 slotId を先に集め、
            // findAllById で一括取得してから slot マップを作る（AC-5: personalTimetableId も引く）。
            Map<Long, PersonalTimetableSlotEntity> slotsBySlotId = resolveSlotsByExternalRefs(targets);
            for (ScheduleEntity sch : targets) {
                sch.softDelete();
                scheduleRepository.save(sch);
                if (sch.getUserId() != null) {
                    try {
                        NotificationDeliveryRequest request = buildRevokedNotificationRequest(sch, slotsBySlotId);
                        if (request != null) {
                            notificationRequests.add(request);
                        }
                    } catch (Exception ex) {
                        // 通知組み立て失敗は当該スケジュールだけを隔離する（他の取消対象の
                        // softDelete/save・通知には影響させない）。
                        log.warn("取消通知の組み立てに失敗（継続）: scheduleId={}, userId={}, error={}",
                                sch.getId(), sch.getUserId(), ex.getMessage());
                    }
                }
            }
            log.info("臨時変更削除に伴う個人スケジュール削除: changeId={}, count={}",
                    event.getChangeId(), targets.size());
        } catch (Exception ex) {
            log.error("PersonalTimetableLinkSyncListener.onChangeDeleted 失敗: changeId={}, error={}",
                    event.getChangeId(), ex.getMessage(), ex);
        } finally {
            publishNotifications(notificationRequests);
        }
    }

    /**
     * 通知配送要求の一覧を {@link PersonalTimetableSyncNotificationEvent} として publish する。
     *
     * <p>本メソッドは第1段の {@code REQUIRES_NEW} トランザクションの内側で呼ばれる。
     * 空リストなら publish しない（無駄な第2段起動を避ける）。</p>
     */
    private void publishNotifications(List<NotificationDeliveryRequest> notificationRequests) {
        if (!notificationRequests.isEmpty()) {
            eventPublisher.publishEvent(new PersonalTimetableSyncNotificationEvent(notificationRequests));
        }
    }

    private NotificationDeliveryRequest processSlotForChange(PersonalTimetableSlotEntity slot,
                                       TimetableChangeEntity change,
                                       LocalDate targetDate) {
        // ユーザー設定 / コマ設定 / 親 ACTIVE の三段ガード
        if (!Boolean.TRUE.equals(slot.getAutoSyncChanges())) {
            return null;
        }
        PersonalTimetableEntity personal = personalTimetableRepository
                .findById(slot.getPersonalTimetableId()).orElse(null);
        if (personal == null || personal.getDeletedAt() != null
                || personal.getStatus() != PersonalTimetableStatus.ACTIVE) {
            return null;
        }
        Optional<PersonalTimetableSettingsEntity> settingsOpt =
                settingsRepository.findById(personal.getUserId());
        boolean autoReflect = settingsOpt
                .map(PersonalTimetableSettingsEntity::getAutoReflectClassChangesToCalendar)
                .orElse(true);
        if (!autoReflect) {
            return null;
        }

        // 適用期間チェック
        if (personal.getEffectiveFrom() != null && targetDate.isBefore(personal.getEffectiveFrom())) {
            return null;
        }
        if (personal.getEffectiveUntil() != null && targetDate.isAfter(personal.getEffectiveUntil())) {
            return null;
        }

        // 曜日チェック（個人コマの dayOfWeek と targetDate の曜日が一致する必要がある）
        String targetDow = WEEK_DOWS.get(targetDate.getDayOfWeek().getValue() - 1);
        if (!targetDow.equals(slot.getDayOfWeek())) {
            return null;
        }

        // DAY_OFF 以外は period_number 一致チェック
        if (change.getChangeType() != TimetableChangeType.DAY_OFF
                && change.getPeriodNumber() != null
                && !change.getPeriodNumber().equals(slot.getPeriodNumber())) {
            return null;
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
                return null;
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

        // F04.3 通知（受信者 locale に従って件名を組み立てる。Issue #2715 ロットB）。
        // 通知先スケジュールは本メソッド内で今まさに save したエイリブな行のため、
        // sourceType=SCHEDULE のまま Resolver ガードを通しても問題ない。
        // 通知の組み立て失敗（locale解決の DataAccessException 等）はこの1コマ分だけ隔離し、
        // 他のリンク済みコマの反映処理・通知には影響させない（PR #2809 検分差し戻し①と同じ隔離範囲）。
        try {
            Locale locale = resolveLocale(personal.getUserId());
            String notifTitle = buildSyncedNotificationTitle(change, slot, locale);
            String notifBody = messageSource != null
                    ? messageSource.getMessage(
                            "notification.timetable.personalLink.synced.body",
                            new Object[]{slot.getSubjectName(), targetDate.toString()},
                            slot.getSubjectName() + "（" + targetDate + "）", locale)
                    : slot.getSubjectName() + "（" + targetDate + "）";
            return new NotificationDeliveryRequest(
                    personal.getUserId(),
                    SYNCED_NOTIFICATION_TYPE,
                    NotificationPriority.NORMAL,
                    notifTitle,
                    notifBody,
                    "SCHEDULE", entity.getId(),
                    NotificationScopeType.PERSONAL, personal.getUserId(),
                    "/schedules/" + (entity.getId() == null ? "" : entity.getId()),
                    null);
        } catch (Exception ex) {
            log.warn("同期通知の組み立てに失敗（継続）: userId={}, error={}",
                    personal.getUserId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 取消通知の配送要求を組み立てる（AC-5）。
     *
     * <p>{@code sourceType=SCHEDULE} + 削除済み {@code scheduleId} は使わない（クラス javadoc 参照）。
     * 生存中の {@code personalTimetableId} を参照する。personalTimetableId が復元できない場合
     * （slot が既に削除されている等）は通知を作らない（fail-closed・迂回しない）。</p>
     */
    private NotificationDeliveryRequest buildRevokedNotificationRequest(
            ScheduleEntity sch, Map<Long, PersonalTimetableSlotEntity> slotsBySlotId) {
        Long slotId = parseSlotIdFromExternalRef(sch.getExternalRef());
        PersonalTimetableSlotEntity slot = slotId != null ? slotsBySlotId.get(slotId) : null;
        Long personalTimetableId = slot != null ? slot.getPersonalTimetableId() : null;
        if (personalTimetableId == null) {
            log.warn("取消通知の personalTimetableId を復元できずスキップ: scheduleId={}, externalRef={}",
                    sch.getId(), sch.getExternalRef());
            return null;
        }

        Locale locale = resolveLocale(sch.getUserId());
        String notifTitle = messageSource != null
                ? messageSource.getMessage(
                        "notification.timetable.personalLink.revoked.title", null,
                        "授業変更が取り消されました", locale)
                : "授業変更が取り消されました";
        String notifBody = buildRevokedNotificationBody(sch, locale, slot);

        return new NotificationDeliveryRequest(
                sch.getUserId(),
                REVOKED_NOTIFICATION_TYPE,
                NotificationPriority.NORMAL,
                notifTitle,
                notifBody,
                REVOKED_SOURCE_TYPE, personalTimetableId,
                NotificationScopeType.PERSONAL, sch.getUserId(),
                "/me/personal-timetable/" + personalTimetableId,
                null);
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
     * かわりに {@code externalRef}（{@code "F03.15:{changeId}:{slotId}"}）から復元した slot を
     * キーに（{@link #resolveSlotsByExternalRefs}）、synced 側の
     * {@code notification.timetable.personalLink.synced.body} と同じ
     * {@code {0}}=科目名, {@code {1}}=日付 の作りで組み立てる。</p>
     */
    private String buildRevokedNotificationBody(ScheduleEntity sch, Locale locale,
                                                  PersonalTimetableSlotEntity slot) {
        String subjectName = slot != null ? slot.getSubjectName() : null;
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
     * {@code slotId -> slot} のマップを組み立てる（PR #2809 検分二次: N+1 是正。
     * AC-5: personalTimetableId も同じマップから引く）。
     *
     * <p>取消対象1件ごとに {@code findById} していた旧実装は、チーム時間割にリンクする
     * 個人コマ数（＝取消対象数）に比例して SQL 発行数が増えるため、書き込みトランザクション内の
     * 遅延・ロック保持時間が伸びる問題があった。</p>
     */
    private Map<Long, PersonalTimetableSlotEntity> resolveSlotsByExternalRefs(List<ScheduleEntity> targets) {
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
                        s -> s));
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
