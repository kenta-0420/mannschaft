package com.mannschaft.app.village.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.dto.MeetupAttendanceUpsertRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMeetupAttendanceRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17.2 追補 — 寄合定員 <strong>並行</strong>制御の試練テスト（AC-20・red 先行）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>試練（red）テスト</strong>。定員=1 の CONFIRMED 寄合に対し、2 村人が
 * ほぼ同時に GOING を叩く。悲観ロックによる定員強制が<strong>未実装</strong>の現状では、
 * 2 スレッドとも通過して GOING 行が 2 件になり、「成功が capacity 以下（=1）に収まる」という
 * 不変条件を破って red になる。出陣フェーズで悲観ロック（{@code SELECT ... FOR UPDATE} 等）を
 * 実装すると、一方だけ成功・他方は VILLAGE_103 で弾かれ、GOING 行数が capacity(=1) に収まって green 化する。</p>
 *
 * <p><strong>@Transactional を付けない</strong>のが要点。契約 IT（{@link com.mannschaft.app.village.controller.VillageMeetupCapacityContractIT}）は
 * クラス @Transactional でロールバックするが、本テストは別スレッドから<strong>コミット済みの状態</strong>を
 * 見せて競わせる必要があるため、セットアップを実コミットし、{@link #tearDown()} で後始末する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 追補 寄合定員 並行制御テスト（試練・red・AC-20）")
class VillageMeetupCapacityConcurrencyIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private VillageMeetupService meetupService;
    @Autowired
    private VillageRepository villageRepository;
    @Autowired
    private VillageMembershipRepository membershipRepository;
    @Autowired
    private VillageMeetupRepository meetupRepository;
    @Autowired
    private VillageMeetupAttendanceRepository attendanceRepository;

    @MockitoBean
    private R2StorageService r2StorageService;

    private static final Long U1 = 17_204_001L;
    private static final Long U2 = 17_204_002L;

    private UUID villageId;
    private UUID meetupId;

    @AfterEach
    void tearDown() {
        // コミット済みのため明示的に後始末する（他テストへの汚染防止）。
        if (meetupId != null) {
            attendanceRepository.findByMeetupIdOrderByCreatedAtAsc(
                            meetupId, org.springframework.data.domain.PageRequest.of(0, 100))
                    .forEach(attendanceRepository::delete);
            meetupRepository.findById(meetupId).ifPresent(meetupRepository::delete);
        }
        if (villageId != null) {
            membershipRepository.findAll().stream()
                    .filter(m -> villageId.equals(m.getVillageId()))
                    .forEach(membershipRepository::delete);
            villageRepository.findById(villageId).ifPresent(villageRepository::delete);
        }
    }

    @Test
    @DisplayName("AC-20 capacity=1 に 2 村人が同時 GOING → 成功する GOING は capacity(=1) 以下に収まる")
    void concurrentGoing_staysWithinCapacity_AC20() throws Exception {
        // ── セットアップ（実コミット）──────────────────────────────────
        VillageEntity v = villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("capcc-" + Long.toHexString(System.nanoTime()))
                .name("並行定員村" + System.nanoTime())
                .description("寄合定員並行テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(U1)
                .build());
        villageId = v.getId();

        membershipRepository.saveAndFlush(membership(villageId, U1));
        membershipRepository.saveAndFlush(membership(villageId, U2));

        VillageMeetupEntity meetup = meetupRepository.saveAndFlush(VillageMeetupEntity.builder()
                .villageId(villageId)
                .title("並行寄合" + System.nanoTime())
                .organizerUserId(U1)
                .status(VillageMeetupStatus.CONFIRMED)
                .confirmedDate(LocalDate.of(2026, 8, 1))
                .capacity(1)
                .build());
        meetupId = meetup.getId();

        // ── 2 スレッドで同時に GOING を叩く ───────────────────────────
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> goingWithBarrier(barrier, U1));
            Future<?> f2 = pool.submit(() -> goingWithBarrier(barrier, U2));
            // 例外（VILLAGE_103 等）はここでは握り、最終的な GOING 行数で不変条件を検証する。
            awaitQuietly(f1);
            awaitQuietly(f2);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        // 不変条件: 成功した GOING は capacity(=1) を超えない。
        // 現状（enforcement 未実装）は両者成功 → 2 件で red。悲観ロック実装後は 1 件で green。
        long going = attendanceRepository.countByMeetupIdAndStatus(
                meetupId, VillageMeetupAttendanceStatus.GOING);
        assertThat(going)
                .as("同時 GOING 後の GOING 行数は capacity(=1) 以下に収まるべき")
                .isLessThanOrEqualTo(1);
    }

    private void goingWithBarrier(CyclicBarrier barrier, Long userId) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            meetupService.upsertAttendance(villageId, meetupId,
                    new MeetupAttendanceUpsertRequest(VillageMeetupAttendanceStatus.GOING), userId);
        } catch (Exception ignored) {
            // 定員超過は VILLAGE_103（BusinessException）で弾かれる想定。
            // 本テストは「成功総数 ≤ capacity」を最終行数で検証するため、ここでは握る。
        }
    }

    private void awaitQuietly(Future<?> f) {
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 個々の失敗は最終行数の検証に委ねる。
        }
    }

    private VillageMembershipEntity membership(UUID vId, Long userId) {
        return VillageMembershipEntity.builder()
                .villageId(vId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
    }
}
