package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.event.TimetableChangeCreatedEvent;
import com.mannschaft.app.timetable.event.TimetableChangeDeletedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.timetable.personal.event.PersonalTimetableSyncNotificationEvent;
import com.mannschaft.app.timetable.personal.listener.PersonalTimetableLinkSyncListener;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 4 PersonalTimetableLinkSyncListener のユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableLinkSyncListener ユニットテスト")
class PersonalTimetableLinkSyncListenerTest {

    private static final Long USER_ID = 100L;
    private static final Long PT_ID = 1L;
    private static final Long SLOT_ID = 11L;
    private static final Long TT_ID = 200L;
    private static final Long CHANGE_ID = 300L;

    @Mock private PersonalTimetableSlotRepository personalSlotRepository;
    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private PersonalTimetablePeriodRepository personalPeriodRepository;
    @Mock private PersonalTimetableSettingsRepository settingsRepository;
    @Mock private TimetableChangeRepository timetableChangeRepository;
    @Mock private TimetableSlotRepository timetableSlotRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PersonalTimetableLinkSyncListener listener;

    @BeforeEach
    void setUp() {
        listener = new PersonalTimetableLinkSyncListener(
                personalSlotRepository,
                personalTimetableRepository,
                personalPeriodRepository,
                settingsRepository,
                timetableChangeRepository,
                timetableSlotRepository,
                scheduleRepository,
                eventPublisher);
    }

    /**
     * {@link #eventPublisher} へ publish された唯一の {@link PersonalTimetableSyncNotificationEvent}
     * から通知配送要求一覧を取り出すヘルパー（Issue #2834 / CMP-056 型確立: 通知は
     * {@code NotificationHelper} 直接呼び出しではなく AFTER_COMMIT イベント publish に置き換わった）。
     */
    private List<NotificationDeliveryRequest> capturedNotificationRequests() {
        ArgumentCaptor<PersonalTimetableSyncNotificationEvent> captor =
                ArgumentCaptor.forClass(PersonalTimetableSyncNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue().requests();
    }

    private static PersonalTimetableSlotEntity buildSlot(String dow, int period) {
        PersonalTimetableSlotEntity s = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(PT_ID)
                .dayOfWeek(dow)
                .periodNumber(period)
                .weekPattern(WeekPattern.EVERY)
                .subjectName("ドイツ語Ⅰ")
                .roomName("L棟401")
                .autoSyncChanges(true)
                .linkedTeamId(50L)
                .linkedTimetableId(TT_ID)
                .build();
        try {
            Field idField = s.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, SLOT_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return s;
    }

    private static PersonalTimetableEntity buildPersonal() {
        return buildPersonalOwnedBy(USER_ID);
    }

    private static PersonalTimetableEntity buildPersonalOwnedBy(Long ownerUserId) {
        PersonalTimetableEntity p = PersonalTimetableEntity.builder()
                .userId(ownerUserId)
                .name("test")
                .status(PersonalTimetableStatus.ACTIVE)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveUntil(LocalDate.of(2026, 12, 31))
                .build();
        try {
            Field idField = p.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(p, PT_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return p;
    }

    private static PersonalTimetablePeriodEntity buildPeriod(int periodNumber) {
        return PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(PT_ID)
                .periodNumber(periodNumber)
                .label(periodNumber + "限")
                .startTime(LocalTime.of(13, 0))
                .endTime(LocalTime.of(14, 30))
                .isBreak(false)
                .build();
    }

    private static TimetableChangeEntity buildChange(TimetableChangeType type, LocalDate date, Integer period) {
        TimetableChangeEntity c = TimetableChangeEntity.builder()
                .timetableId(TT_ID)
                .targetDate(date)
                .periodNumber(period)
                .changeType(type)
                .reason("教員体調不良")
                .notifyMembers(true)
                .build();
        try {
            Field idField = c.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, CHANGE_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return c;
    }

    @Test
    @DisplayName("CANCEL: 個人スケジュールに [休講] レコードを INSERT する")
    void onChangeCreated_CANCEL_INSERT() {
        // 2026-05-04 は MON
        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity change = buildChange(TimetableChangeType.CANCEL, target, 3);

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(change));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.empty());
        given(personalSlotRepository.findByLinkedTimetableId(TT_ID))
                .willReturn(List.of(buildSlot("MON", 3)));
        given(personalTimetableRepository.findById(PT_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty()); // デフォルト ON
        given(personalPeriodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(PT_ID))
                .willReturn(List.of(buildPeriod(3)));
        given(scheduleRepository.findByExternalRef(any())).willReturn(Optional.empty());
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        verify(personalSlotRepository).findByLinkedTimetableId(TT_ID);
        verify(personalTimetableRepository).findById(PT_ID);
        verify(settingsRepository).findById(USER_ID);
        verify(personalPeriodRepository).findByPersonalTimetableIdOrderByPeriodNumberAsc(PT_ID);
        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleRepository).save(captor.capture());
        ScheduleEntity saved = captor.getValue();
        assertThat(saved.getTitle()).contains("[休講]");
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getExternalRef()).isEqualTo("F03.15:" + CHANGE_ID + ":" + SLOT_ID);
    }

    @Test
    @DisplayName("DAY_OFF と CANCEL の重複時、CANCEL は無視される")
    void onChangeCreated_DAY_OFF優先() {
        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity cancel = buildChange(TimetableChangeType.CANCEL, target, 3);
        TimetableChangeEntity dayOff = TimetableChangeEntity.builder()
                .timetableId(TT_ID)
                .targetDate(target)
                .periodNumber(null)
                .changeType(TimetableChangeType.DAY_OFF)
                .build();

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(cancel));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.of(dayOff));

        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("auto_sync_changes = false のコマはスキップ")
    void onChangeCreated_auto_sync_OFF() {
        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity change = buildChange(TimetableChangeType.CANCEL, target, 3);
        PersonalTimetableSlotEntity slot = buildSlot("MON", 3);
        slot.linkTo(50L, TT_ID, null, false); // auto_sync OFF

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(change));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.empty());
        given(personalSlotRepository.findByLinkedTimetableId(TT_ID)).willReturn(List.of(slot));

        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Issue #2715 ロットB: 受信者 locale が en の場合、同期通知の件名・本文が英語で組み立てられプレースホルダが残らない")
    void 受信者ロケールがenなら英語件名本文になる() throws ReflectiveOperationException {
        var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");

        com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache =
                org.mockito.Mockito.mock(com.mannschaft.app.common.i18n.UserLocaleCache.class);
        given(userLocaleCache.getLocale(USER_ID)).willReturn("en");

        setPrivateField(listener, "userLocaleCache", userLocaleCache);
        setPrivateField(listener, "messageSource", realMessageSource);

        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity change = buildChange(TimetableChangeType.CANCEL, target, 3);

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(change));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.empty());
        given(personalSlotRepository.findByLinkedTimetableId(TT_ID))
                .willReturn(List.of(buildSlot("MON", 3)));
        given(personalTimetableRepository.findById(PT_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());
        given(personalPeriodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(PT_ID))
                .willReturn(List.of(buildPeriod(3)));
        given(scheduleRepository.findByExternalRef(any())).willReturn(Optional.empty());
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        // Issue #2834 / CMP-056: 通知は NotificationHelper 直接呼び出しではなく
        // AFTER_COMMIT で配送される PersonalTimetableSyncNotificationEvent の publish に置き換わった。
        // 「サービス（本リスナー第1段）がイベントを publish すること」をここで検証する。
        List<NotificationDeliveryRequest> requests = capturedNotificationRequests();
        assertThat(requests).hasSize(1);
        NotificationDeliveryRequest request = requests.get(0);
        assertThat(request.notificationType()).isEqualTo("TIMETABLE_CHANGE_SYNCED");
        assertThat(request.title()).contains("Cancelled").doesNotContain("{0}");
        assertThat(request.body()).doesNotContain("{0}").doesNotContain("{1}");
    }

    private static void setPrivateField(Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("CANCEL 削除時: external_ref で紐付くスケジュールを論理削除")
    void onChangeDeleted_論理削除() {
        ScheduleEntity sch = ScheduleEntity.builder()
                .userId(USER_ID).title("[休講] X").externalRef("F03.15:" + CHANGE_ID + ":" + SLOT_ID)
                .build();
        given(scheduleRepository.findByExternalRefPrefix("F03.15:" + CHANGE_ID + ":%"))
                .willReturn(List.of(sch));
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        listener.onChangeDeleted(new TimetableChangeDeletedEvent(CHANGE_ID, TT_ID));

        verify(scheduleRepository).save(any(ScheduleEntity.class));
    }

    @Test
    @DisplayName("PR #2809 検分差し戻し①: locale解決がDataAccessExceptionを投げても"
            + "メソッドへ例外が伝播せずscheduleRepository.saveは呼ばれる"
            + "（notify()呼び出し自体は隔離されるが、同一トランザクション内でのDB書き込みの"
            + "rollback-only化は本テストでは再現できない。実トランザクション境界の問題は #2834）")
    void locale解決失敗でも例外が伝播せずsaveが呼ばれる() throws ReflectiveOperationException {
        com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache =
                org.mockito.Mockito.mock(com.mannschaft.app.common.i18n.UserLocaleCache.class);
        given(userLocaleCache.getLocale(USER_ID))
                .willThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"));

        setPrivateField(listener, "userLocaleCache", userLocaleCache);

        LocalDate target = LocalDate.of(2026, 5, 4);
        TimetableChangeEntity change = buildChange(TimetableChangeType.CANCEL, target, 3);

        given(timetableChangeRepository.findById(CHANGE_ID)).willReturn(Optional.of(change));
        given(timetableChangeRepository
                .findByTimetableIdAndTargetDateAndPeriodNumberIsNull(TT_ID, target))
                .willReturn(Optional.empty());
        given(personalSlotRepository.findByLinkedTimetableId(TT_ID))
                .willReturn(List.of(buildSlot("MON", 3)));
        given(personalTimetableRepository.findById(PT_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());
        given(personalPeriodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(PT_ID))
                .willReturn(List.of(buildPeriod(3)));
        given(scheduleRepository.findByExternalRef(any())).willReturn(Optional.empty());
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // 例外が呼び出し元へ伝播せず、かつスケジュールの save が呼ばれることを確認する
        // （修正前は resolveLocale の例外を包む try が processSlotForChange の notify 呼び出し
        //   より外にあり、通知の組み立て失敗がループ全体・save 呼び出しに影響し得た）。
        // 注意: これはモックによるユニットテストであり、実 DB トランザクションの
        //   rollback-only 化は再現できない。同一トランザクション内で notify() が REQUIRED で
        //   参加してしまう構造上の問題（catch しても JPA 側は既に rollback-only）は
        //   このテストでは検証できておらず、別issue #2834 で対応する。
        listener.onChangeCreated(new TimetableChangeCreatedEvent(
                CHANGE_ID, TT_ID, null, TimetableChangeType.CANCEL, target, true, true));

        verify(scheduleRepository).save(any(ScheduleEntity.class));
        // 通知の組み立て（locale解決）に失敗した場合、その1コマ分の通知は隔離されて publish されない。
        // 「リスナーが通知を呼ぶこと」の裏返し（呼ばれない）を検証する。
        verify(eventPublisher, never()).publishEvent(any(PersonalTimetableSyncNotificationEvent.class));
    }

    @Test
    @DisplayName("PR #2809 検分差し戻し②: 取消通知の本文もen localeで英語化されプレースホルダが残らない")
    void 取消通知の本文もenロケールで英語化される() throws ReflectiveOperationException {
        var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");

        com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache =
                org.mockito.Mockito.mock(com.mannschaft.app.common.i18n.UserLocaleCache.class);
        given(userLocaleCache.getLocale(USER_ID)).willReturn("en");

        setPrivateField(listener, "userLocaleCache", userLocaleCache);
        setPrivateField(listener, "messageSource", realMessageSource);

        ScheduleEntity sch = ScheduleEntity.builder()
                .userId(USER_ID).title("[休講] ドイツ語Ⅰ")
                .startAt(java.time.LocalDateTime.of(2026, 5, 4, 13, 0))
                .externalRef("F03.15:" + CHANGE_ID + ":" + SLOT_ID)
                .build();
        given(scheduleRepository.findByExternalRefPrefix("F03.15:" + CHANGE_ID + ":%"))
                .willReturn(List.of(sch));
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        // N+1 是正（PR #2809 検分二次）で findById から findAllById 一括取得に変更したため、
        // 取消対象1件のみでも findAllById をモックする。
        given(personalSlotRepository.findAllById(List.of(SLOT_ID)))
                .willReturn(List.of(buildSlot("MON", 3)));
        // マスター検分指摘の安全弁: 受信者が personalTimetableId の所有者であることの検証に必要。
        given(personalTimetableRepository.findById(PT_ID)).willReturn(Optional.of(buildPersonal()));

        listener.onChangeDeleted(new TimetableChangeDeletedEvent(CHANGE_ID, TT_ID));

        List<NotificationDeliveryRequest> requests = capturedNotificationRequests();
        assertThat(requests).hasSize(1);
        NotificationDeliveryRequest request = requests.get(0);
        assertThat(request.notificationType()).isEqualTo("TIMETABLE_CHANGE_REVOKED");
        assertThat(request.body())
                .contains("2026-05-04")
                .doesNotContain("{0}")
                .doesNotContain("{1}")
                .doesNotContain("[休講]");
        // AC-5: 取消通知は削除済み SCHEDULE を参照しない。生存中の personalTimetableId を参照する。
        assertThat(request.sourceType()).isEqualTo("PERSONAL_TIMETABLE_SYNC_REVOKED");
        assertThat(request.sourceId()).isEqualTo(PT_ID);
        assertThat(request.actionUrl()).isEqualTo("/me/personal-timetable/" + PT_ID);
    }

    @Test
    @DisplayName("マスター検分指摘の安全弁: 受信者が personalTimetableId の所有者と一致しない場合、取消通知は作られない"
            + "（sourceType=PERSONAL_TIMETABLE_SYNC_REVOKED は NotificationSourceTypeMapper 未登録のため"
            + "visibility ガードを素通りする。ガード不在下で受信者導出が壊れても無条件通過させない安全弁の検証）")
    void 受信者と所有者が不一致なら取消通知は作られない() {
        ScheduleEntity sch = ScheduleEntity.builder()
                .userId(USER_ID).title("[休講] ドイツ語Ⅰ")
                .startAt(java.time.LocalDateTime.of(2026, 5, 4, 13, 0))
                .externalRef("F03.15:" + CHANGE_ID + ":" + SLOT_ID)
                .build();
        given(scheduleRepository.findByExternalRefPrefix("F03.15:" + CHANGE_ID + ":%"))
                .willReturn(List.of(sch));
        given(scheduleRepository.save(any(ScheduleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(personalSlotRepository.findAllById(List.of(SLOT_ID)))
                .willReturn(List.of(buildSlot("MON", 3)));

        // personalTimetableId(PT_ID) の所有者は別ユーザー（USER_ID とは異なる）に差し替える。
        given(personalTimetableRepository.findById(PT_ID))
                .willReturn(Optional.of(buildPersonalOwnedBy(999L)));

        listener.onChangeDeleted(new TimetableChangeDeletedEvent(CHANGE_ID, TT_ID));

        // スケジュールの論理削除自体は継続される（安全弁は通知だけを止める）。
        verify(scheduleRepository).save(any(ScheduleEntity.class));
        // 本丸: 受信者(USER_ID) と所有者(999L) が不一致のため、取消通知は publish されない。
        verify(eventPublisher, never()).publishEvent(any(PersonalTimetableSyncNotificationEvent.class));
    }
}
