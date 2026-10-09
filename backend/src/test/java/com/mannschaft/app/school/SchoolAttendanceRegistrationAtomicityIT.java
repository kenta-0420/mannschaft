package com.mannschaft.app.school;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 学校出欠の認可是正 第1段 — 登録の原子性（途中で不正な生徒が混ざったら全件巻き戻る）（試練・red）。
 *
 * <p>対応 AC: AC-12（entries の途中に不正な生徒 ID → 4xx で全体ロールバック・部分登録なし）/
 * AC-10 の「許可・不許可の混在入力は全件ロールバックし、アラートも残さない」。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けないか</h2>
 * <p>テストをトランザクションで包むと、業務メソッドの REQUIRED はテストのトランザクションに参加するだけで、
 * 例外後も「先に INSERT した行」が同一トランザクションから見えてしまい、巻き戻りを検証できない
 * （部分登録する実装でも緑になる）。ここでは世界を TransactionTemplate で実コミットし、
 * 検証も別トランザクションで読み直し、終了時に投入した行を全て消す。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("学校出欠 登録の原子性（第1段・実コミット）")
class SchoolAttendanceRegistrationAtomicityIT extends SchoolAttendanceAuthzFixture {

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> seedWorld());
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> purgeWorld());
    }

    private long countTx(String sql, Object... params) {
        return tx.execute(s -> count(sql, params));
    }

    private String textTx(String sql, Object... params) {
        return tx.execute(s -> text(sql, params));
    }

    @Test
    @DisplayName("AC-12: 点呼の途中に非在籍の生徒が混ざると 4xx・先頭の正当な生徒の行も残らない（全件ロールバック）")
    void 点呼は途中の不正で全件巻き戻る() throws Exception {
        var day = date.plusDays(7);
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/daily/roll-call", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(day, entry(studentAId, "ATTENDING"), entry(strayUserId, "ABSENT")))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("4xx で拒否").isBetween(400, 499);
        assertThat(countTx("SELECT COUNT(*) FROM daily_attendance_records WHERE team_id = ?1 AND attendance_date = ?2",
                teamAId, day)).as("部分登録なし（先頭の正当な生徒の行も残らない）").isZero();
    }

    @Test
    @DisplayName("AC-12/AC-10: 時限登録の途中に非在籍の生徒が混ざると 4xx・既存レコードも新規行もアラートも残らない")
    void 時限登録は途中の不正で全件巻き戻る() throws Exception {
        long alertsBefore = countTx("SELECT COUNT(*) FROM attendance_transition_alerts WHERE team_id = ?1", teamAId);
        auth(Actor.HOMEROOM);

        // 1 時限目の既存レコード（出席）を欠席へ書き換える upsert ＋ 2 時限目の新規欠席（移動検知の発火条件）＋ 不正な生徒。
        MvcResult upsertExisting = mockMvc.perform(post("/api/v1/teams/{t}/attendance/periods/1", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, entry(studentAId, "ABSENT"), entry(strayUserId, "ABSENT")))))
                .andReturn();
        MvcResult newPeriod = mockMvc.perform(post("/api/v1/teams/{t}/attendance/periods/2", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(entriesBody(date, entry(studentAId, "ABSENT"), entry(strayUserId, "ABSENT")))))
                .andReturn();

        assertThat(upsertExisting.getResponse().getStatus()).isBetween(400, 499);
        assertThat(newPeriod.getResponse().getStatus()).isBetween(400, 499);
        assertThat(textTx("SELECT status FROM period_attendance_records WHERE id = ?1", periodAId))
                .as("既存レコードの upsert 更新も巻き戻る").isEqualTo("ATTENDING");
        assertThat(countTx("SELECT COUNT(*) FROM period_attendance_records WHERE team_id = ?1 AND period_number = 2",
                teamAId)).as("新規行も残らない").isZero();
        assertThat(countTx("SELECT COUNT(*) FROM attendance_transition_alerts WHERE team_id = ?1", teamAId))
                .as("移動検知アラートも残らない").isEqualTo(alertsBefore);
    }
}
