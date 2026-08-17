package com.mannschaft.app.returnstayplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanRepository;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** F02.11 Testcontainers MySQL 試練（AC-13, AC-31, AC-32）。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReturnStayPlanPersistenceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReturnStayPlanService service;

    @Autowired
    private ReturnStayPlanRepository planRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("AC-13 公開OFFの行・入力保持・公開先0件を実DBで確認する")
    void ac13_offPlanIsPersistedWithoutVisibilityRows() {
        long ownerId = 91013L;
        var created = service.create(ownerId, validRequest(false, 30L));

        assertThat(created.getOwnerUserId()).isEqualTo(ownerId);
        assertThat(created.getPublished()).isFalse();
        assertThat(created.getCountryCode()).isEqualTo("JP");
        assertThat(created.getPrefectureCode()).isEqualTo("13");
        assertThat(created.getTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from return_stay_plans where owner_user_id = ?",
                Long.class, ownerId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from return_stay_plan_team_visibilities where plan_id = ?",
                Long.class, created.getId())).isZero();
    }

    @Test
    @DisplayName("AC-31 owner lock raceは上限直前の並行結果を直列化しrow countを保つ")
    void ac31_ownerLockRaceIsSerializedInDatabase() throws InterruptedException {
        long ownerId = 91031L;
        planRepository.saveAllAndFlush(IntStream.range(0, 29)
                .mapToObj(index -> plan(ownerId))
                .toList());

        var gate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> createAfterGate(gate, ownerId, 31L));
            Future<?> second = executor.submit(() -> createAfterGate(gate, ownerId, 32L));
            gate.countDown();

            int successCount = 0;
            int rejectedCount = 0;
            for (Future<?> result : List.of(first, second)) {
                try {
                    result.get();
                    successCount++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
                    rejectedCount++;
                }
            }
            assertThat(successCount).isEqualTo(1);
            assertThat(rejectedCount).isEqualTo(1);
        }
        assertThat(planRepository.countByOwnerUserId(ownerId)).isEqualTo(30L);
    }

    @Test
    @DisplayName("AC-32 owner lockの並行作成はdeadlockせず両ownerのrowを保持する")
    void ac32_ownerLockRaceDoesNotDeadlock() {
        long firstOwner = 91032L;
        long secondOwner = 91033L;
        var gate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> createAfterGate(gate, firstOwner, 41L));
            Future<?> second = executor.submit(() -> createAfterGate(gate, secondOwner, 42L));
            gate.countDown();
            first.get();
            second.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("並行作成が割り込みで終了した", exception);
        } catch (ExecutionException exception) {
            throw new AssertionError("owner lockの並行作成で例外が発生した", exception.getCause());
        }
        assertThat(planRepository.countByOwnerUserId(firstOwner)).isEqualTo(1L);
        assertThat(planRepository.countByOwnerUserId(secondOwner)).isEqualTo(1L);
    }

    private void createAfterGate(CountDownLatch gate, long ownerId, long teamId) {
        try {
            gate.await();
            service.create(ownerId, validRequest(false, teamId));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("並行作成が割り込みで終了した", exception);
        }
    }

    private ReturnStayPlanCreateRequest validRequest(boolean published, long teamId) {
        return new ReturnStayPlanCreateRequest(
                "HOMECOMING",
                published,
                new ReturnStayPlanCreateRequest.Location("JP", "13", null),
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 20),
                published ? List.of(teamId) : List.of());
    }

    private ReturnStayPlanEntity plan(long ownerId) {
        return ReturnStayPlanEntity.builder()
                .ownerUserId(ownerId)
                .planType(ReturnStayPlanEntity.PlanType.HOMECOMING)
                .published(false)
                .countryCode("JP")
                .prefectureCode("13")
                .timezone("Asia/Tokyo")
                .startDate(LocalDate.of(2026, 8, 17))
                .endDate(LocalDate.of(2026, 8, 20))
                .build();
    }
}
