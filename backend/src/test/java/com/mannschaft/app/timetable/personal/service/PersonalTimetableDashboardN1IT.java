package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.TimetableStatus;
import com.mannschaft.app.timetable.TimetableVisibility;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse.TimetableTodayItem;
import com.mannschaft.app.timetable.personal.dto.TimetableWeekSlotInfo;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 今日の時間割（GET /api/v1/me/dashboard/timetable-today）・週全体スロット一覧の N+1 解消を固定する試練
 * （CMP-261103-1225 / #4270 P1-E の AC-1〜AC-6）。
 *
 * <p>SQL 数が「所属チーム数・時間割数・当日のコマ数・異なる所属 ID」に依存しないことを
 * Hibernate {@link Statistics#getPrepareStatementCount()} で検証し、応答の内容
 * （順序・ノート有無・添付有無・臨時変更・link_revoked）が修正前と等価であることを
 * 播種時に確定した期待値で固定する（characterization）。</p>
 */
@Transactional
@DisplayName("今日の時間割・週スロット一覧 N+1 解消（P1-E）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PersonalTimetableDashboardN1IT extends AbstractMySqlIntegrationTest {

    /** 月曜日（"MON"）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    @Autowired
    private PersonalTimetableDashboardService service;
    @PersistenceContext
    private EntityManager em;

    /** 1 コマ分の期待値。 */
    private record Expect(String kind, Long noteId, boolean hasAtt, boolean changed, boolean revoked) {
    }

    private static final class Seeded {
        final Map<String, Expect> expects = new HashMap<>();
        int expectedItemCount;
    }

    private Statistics stats() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        return s;
    }

    private Long persistNote(Long userId, TimetableSlotKind kind, Long slotId, LocalDate date) {
        em.persist(TimetableSlotUserNoteEntity.builder()
                .userId(userId).slotKind(kind).slotId(slotId).targetDate(date).freeMemo("m").build());
        em.flush();
        return em.createQuery("SELECT MAX(n.id) FROM TimetableSlotUserNoteEntity n", Long.class)
                .getSingleResult();
    }

    private void persistAttachment(Long userId, Long noteId, boolean deleted) {
        em.persist(TimetableSlotUserNoteAttachmentEntity.builder()
                .noteId(noteId).userId(userId).r2ObjectKey("k/" + noteId + "/" + deleted + "/" + System.nanoTime())
                .originalFilename("a.png").mimeType("image/png").sizeBytes(1L)
                .createdAt(LocalDateTime.now()).deletedAt(deleted ? LocalDateTime.now() : null).build());
    }

    /**
     * 播種。teams × ttPerTeam の TEAM 時間割（各 slotsPerTt 件の MON コマ ＋ TUE 1 件）と、
     * personals 件の個人時間割（各 slotsPerP 件の MON コマ）を作る。
     */
    private Seeded seed(long userId, long teamBase, int teams, int ttPerTeam, int slotsPerTt,
                        int personals, int slotsPerP) {
        Seeded out = new Seeded();
        MembershipTestHelper.insertActiveUser(em, userId);
        List<Long> teamIds = new ArrayList<>();
        List<Long> firstTtOfTeam = new ArrayList<>();
        for (int t = 0; t < teams; t++) {
            long teamId = teamBase + t;
            teamIds.add(teamId);
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            for (int k = 0; k < ttPerTeam; k++) {
                TimetableEntity tt = em.merge(TimetableEntity.builder().teamId(teamId).termId(1L)
                        .name("TT" + teamId + "-" + k).status(TimetableStatus.ACTIVE)
                        .visibility(TimetableVisibility.MEMBERS_ONLY)
                        .effectiveFrom(LocalDate.of(2026, 4, 1)).build());
                em.flush();
                if (k == 0) {
                    firstTtOfTeam.add(tt.getId());
                }
                for (int j = 0; j < slotsPerTt; j++) {
                    TimetableSlotEntity s = em.merge(TimetableSlotEntity.builder().timetableId(tt.getId())
                            .dayOfWeek("MON").periodNumber(j + 1).weekPattern(WeekPattern.EVERY)
                            .subjectName("TS" + teamId + "-" + k + "-" + j).build());
                    em.flush();
                    Long noteId = null;
                    boolean att = false;
                    if (j % 3 == 0) {
                        // 常設メモ ＋ 当日メモ（当日が優先）＋ 添付 1 件
                        persistNote(userId, TimetableSlotKind.TEAM, s.getId(), null);
                        noteId = persistNote(userId, TimetableSlotKind.TEAM, s.getId(), TODAY);
                        persistAttachment(userId, noteId, false);
                        att = true;
                    } else if (j % 3 == 1) {
                        // 常設のみ。添付は論理削除のみ → 添付なし
                        noteId = persistNote(userId, TimetableSlotKind.TEAM, s.getId(), null);
                        persistAttachment(userId, noteId, true);
                    } else {
                        // 別日のメモ・他人のメモは拾わない
                        persistNote(userId, TimetableSlotKind.TEAM, s.getId(), TODAY.minusDays(1));
                        persistNote(userId + 1_000_000, TimetableSlotKind.TEAM, s.getId(), null);
                    }
                    out.expects.put("TEAM:" + s.getId(), new Expect("TEAM", noteId, att, false, false));
                    out.expectedItemCount++;
                }
                em.persist(TimetableSlotEntity.builder().timetableId(tt.getId())
                        .dayOfWeek("TUE").periodNumber(1).weekPattern(WeekPattern.EVERY)
                        .subjectName("TUE" + teamId + "-" + k).build());
            }
        }
        em.flush();

        for (int p = 0; p < personals; p++) {
            PersonalTimetableEntity pt = em.merge(PersonalTimetableEntity.builder().userId(userId)
                    .name("PT" + p).effectiveFrom(LocalDate.of(2026, 4, 1))
                    .status(PersonalTimetableStatus.ACTIVE).visibility(PersonalTimetableVisibility.PRIVATE).build());
            em.flush();
            for (int j = 0; j < slotsPerP; j++) {
                // 同一 period_number の重複行（toMap の重複キー耐性）
                em.persist(PersonalTimetablePeriodEntity.builder().personalTimetableId(pt.getId())
                        .periodNumber(j + 1).label("P" + (j + 1)).startTime(LocalTime.of(8 + p, j * 10))
                        .endTime(LocalTime.of(8 + p, j * 10 + 5)).build());
            }
            em.persist(PersonalTimetablePeriodEntity.builder().personalTimetableId(pt.getId())
                    .periodNumber(1).label("DUP").startTime(LocalTime.of(23, 0)).endTime(LocalTime.of(23, 5)).build());
            for (int j = 0; j < slotsPerP; j++) {
                Long linkedTeam = null;
                Long linkedTt = null;
                boolean changed = false;
                boolean revoked = false;
                if (j % 3 == 0 && !teamIds.isEmpty()) {
                    int idx = (p + j) % teamIds.size();
                    linkedTeam = teamIds.get(idx);
                    linkedTt = firstTtOfTeam.get(idx);
                    // 当日の時限指定変更 → period_number 一致で isChanged
                    changed = true;
                    em.persist(TimetableChangeEntity.builder().timetableId(linkedTt).targetDate(TODAY)
                            .periodNumber(j + 1).changeType(TimetableChangeType.REPLACE).subjectName("X").build());
                    // 別日の変更は無関係
                    em.persist(TimetableChangeEntity.builder().timetableId(linkedTt)
                            .targetDate(TODAY.plusDays(1)).periodNumber(j + 1)
                            .changeType(TimetableChangeType.CANCEL).build());
                } else if (j % 3 == 1) {
                    // 所属していないチームへのリンク → link_revoked。linkedTimetable は DAY_OFF 優先
                    linkedTeam = 880_000L + p * 100 + j;
                    long fakeTt = 990_000L + p * 100 + j;
                    linkedTt = fakeTt;
                    changed = true;
                    revoked = true;
                    em.persist(TimetableChangeEntity.builder().timetableId(fakeTt).targetDate(TODAY)
                            .changeType(TimetableChangeType.DAY_OFF).build());
                }
                PersonalTimetableSlotEntity s = em.merge(PersonalTimetableSlotEntity.builder()
                        .personalTimetableId(pt.getId()).dayOfWeek("MON").periodNumber(j + 1)
                        .weekPattern(WeekPattern.EVERY).subjectName("PS" + p + "-" + j).courseCode("C" + j)
                        .linkedTeamId(linkedTeam).linkedTimetableId(linkedTt).build());
                em.flush();
                Long noteId = null;
                boolean att = false;
                if (j % 2 == 0) {
                    noteId = persistNote(userId, TimetableSlotKind.PERSONAL, s.getId(), TODAY);
                    persistAttachment(userId, noteId, false);
                    persistAttachment(userId, noteId, false);
                    att = true;
                } else {
                    noteId = persistNote(userId, TimetableSlotKind.PERSONAL, s.getId(), null);
                }
                out.expects.put("PERSONAL:" + s.getId(), new Expect("PERSONAL", noteId, att, changed, revoked));
                out.expectedItemCount++;
            }
        }
        em.flush();
        em.clear();
        return out;
    }

    private void assertEquivalent(Seeded seeded, DashboardTimetableTodayResponse res) {
        assertThat(res.items()).hasSize(seeded.expectedItemCount);
        for (TimetableTodayItem i : res.items()) {
            Expect e = seeded.expects.get(i.sourceKind() + ":" + i.slotId());
            assertThat(e).as("想定外のコマ " + i.sourceKind() + ":" + i.slotId()).isNotNull();
            assertThat(i.userNoteId()).as("noteId " + i.slotId()).isEqualTo(e.noteId());
            assertThat(i.hasAttachments()).as("hasAttachments " + i.slotId()).isEqualTo(e.hasAtt());
            assertThat(i.isChanged()).as("isChanged " + i.slotId()).isEqualTo(e.changed());
            assertThat(i.linkRevoked()).as("linkRevoked " + i.slotId()).isEqualTo(e.revoked());
            assertThat(i.change() != null).as("change " + i.slotId()).isEqualTo(e.changed());
        }
        // 開始時刻昇順・null は末尾（個人コマは時限の start_time が入り、TEAM は null で末尾）
        boolean seenNull = false;
        LocalTime prev = null;
        for (TimetableTodayItem i : res.items()) {
            if (i.startTime() == null) {
                seenNull = true;
                continue;
            }
            assertThat(seenNull).as("null startTime は末尾").isFalse();
            if (prev != null) {
                assertThat(i.startTime()).isAfterOrEqualTo(prev);
            }
            prev = i.startTime();
        }
        // 重複 period_number は先頭（period_number 昇順・同値は先勝ち）の時限が採用され、DUP 側の 23:00 は現れない
        assertThat(res.items()).filteredOn(i -> "PERSONAL".equals(i.sourceKind()))
                .allSatisfy(i -> assertThat(i.startTime()).isNotNull());
    }

    @Test
    @DisplayName("AC-1/AC-2: 所属チーム数・時間割数・コマ数・異なる所属IDを増やしても SQL 数は一定で、応答は等価")
    void todaySqlCountIndependentOfFactors() {
        Seeded small = seed(930001L, 931000L, 1, 1, 2, 1, 3);
        Seeded large = seed(930002L, 932000L, 3, 2, 3, 3, 3);

        Statistics s = stats();
        DashboardTimetableTodayResponse smallRes = service.getTimetableToday(930001L, TODAY);
        long smallCount = s.getPrepareStatementCount();
        assertEquivalent(small, smallRes);

        em.clear();
        s.clear();
        DashboardTimetableTodayResponse largeRes = service.getTimetableToday(930002L, TODAY);
        long largeCount = s.getPrepareStatementCount();
        assertEquivalent(large, largeRes);

        System.out.println("P1E today SQL small=" + smallCount + " large=" + largeCount);
        assertThat(largeCount).as("要因を増やしても今日の時間割の SQL 数は同じ").isEqualTo(smallCount);
    }

    @Test
    @DisplayName("AC-1: 週全体スロット一覧（listAllWeekSlots）の SQL 数は所属チーム数・時間割数に依存しない")
    void weekSlotsSqlCountIndependentOfFactors() {
        seed(930011L, 933000L, 1, 1, 2, 1, 3);
        seed(930012L, 934000L, 3, 2, 3, 3, 3);

        Statistics s = stats();
        List<TimetableWeekSlotInfo> smallRes = service.listAllWeekSlots(930011L, TODAY);
        long smallCount = s.getPrepareStatementCount();

        em.clear();
        s.clear();
        List<TimetableWeekSlotInfo> largeRes = service.listAllWeekSlots(930012L, TODAY);
        long largeCount = s.getPrepareStatementCount();

        System.out.println("P1E week SQL small=" + smallCount + " large=" + largeCount);
        // small: TEAM 2 MON + 1 TUE, PERSONAL 3 / large: TEAM 3*2*(3+1)=24, PERSONAL 3*3=9
        assertThat(smallRes.stream().filter(i -> "TEAM".equals(i.kind())).count()).isEqualTo(3);
        assertThat(smallRes.stream().filter(i -> "PERSONAL".equals(i.kind())).count()).isEqualTo(3);
        assertThat(largeRes.stream().filter(i -> "TEAM".equals(i.kind())).count()).isEqualTo(24);
        assertThat(largeRes.stream().filter(i -> "PERSONAL".equals(i.kind())).count()).isEqualTo(9);
        // 曜日順（MON→…→SUN）に TEAM を拾うため、MON の枠が TUE より先に出る
        List<String> smallTeamNames = smallRes.stream().filter(i -> "TEAM".equals(i.kind()))
                .map(TimetableWeekSlotInfo::subjectName).toList();
        assertThat(smallTeamNames).containsExactly("TS933000-0-0", "TS933000-0-1", "TUE933000-0");
        assertThat(largeCount).as("要因を増やしても週スロット一覧の SQL 数は同じ").isEqualTo(smallCount);
    }

    @Test
    @DisplayName("AC-3: 所属なし・コマなし・時間割なしでも空 IN のエラーを出さず空の応答を返す")
    void emptyUserReturnsEmpty() {
        MembershipTestHelper.insertActiveUser(em, 930021L);
        em.flush();
        em.clear();
        DashboardTimetableTodayResponse res = service.getTimetableToday(930021L, TODAY);
        assertThat(res.items()).isEmpty();
        assertThat(res.weekPattern()).isEqualTo("EVERY");
        assertThat(service.listAllWeekSlots(930021L, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("AC-3: 所属チームはあるが時間割・コマが無い／個人時間割のみで所属なしでも正しく動く")
    void teamWithoutTimetableAndPersonalOnly() {
        // 所属あり・時間割なし
        MembershipTestHelper.insertActiveUser(em, 930031L);
        MembershipTestHelper.insertMembership(em, 930031L, ScopeType.TEAM, 935001L, RoleKind.MEMBER);
        // 個人時間割のみ（所属なし、リンクもなし → 空の teamIds 経路）
        Seeded personalOnly = seed(930032L, 936000L, 0, 0, 0, 2, 2);
        em.flush();
        em.clear();
        assertThat(service.getTimetableToday(930031L, TODAY).items()).isEmpty();
        DashboardTimetableTodayResponse res = service.getTimetableToday(930032L, TODAY);
        assertThat(res.items()).hasSize(4);
        assertThat(res.items()).allSatisfy(i -> {
            Expect e = personalOnly.expects.get("PERSONAL:" + i.slotId());
            assertThat(i.userNoteId()).isEqualTo(e.noteId());
            assertThat(i.linkRevoked()).isEqualTo(e.revoked());
            assertThat(i.isChanged()).isEqualTo(e.changed());
        });
    }

    @Test
    @DisplayName("回帰: 当日の個人コマが全て未リンク(linked_timetable_id=null)でも NPE にならず 200 相当で返る")
    void allPersonalSlotsUnlinkedDoesNotThrow() {
        // teams=0・slotsPerP=1 → 唯一のコマ(j=0)は linkedTimetableId=null のまま
        Seeded seeded = seed(930051L, 938000L, 0, 0, 0, 1, 1);
        em.flush();
        em.clear();
        DashboardTimetableTodayResponse res = service.getTimetableToday(930051L, TODAY);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items()).allSatisfy(i -> {
            Expect e = seeded.expects.get("PERSONAL:" + i.slotId());
            assertThat(i.isChanged()).isFalse();
            assertThat(i.linkRevoked()).isEqualTo(e.revoked());
        });
    }

    @Test
    @DisplayName("AC-4/AC-5: 同一メモ・同一時間割への複数リンクと、存在しない時限・null リンクで例外を出さない")
    void duplicateKeysAndNullLinksAreSafe() {
        Seeded seeded = seed(930041L, 937000L, 1, 1, 1, 1, 6);
        // 同じ linkedTimetable に向く別コマを追加しても toMap 重複で落ちない（seed 済みコマ j=0,3 が同一時間割を共有）
        DashboardTimetableTodayResponse res = service.getTimetableToday(930041L, TODAY);
        assertEquivalent(seeded, res);
    }
}
