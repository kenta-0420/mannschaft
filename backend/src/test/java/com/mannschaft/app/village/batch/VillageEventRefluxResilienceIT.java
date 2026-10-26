package com.mannschaft.app.village.batch;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.village.service.VillageEventArchiveService;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F17.2 Wave2 ①/③ — 副作用（自動投稿・村史編纂）失敗時の分離耐性テスト（設計書 §3.3.1／§5.5）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p>設計の核心は「自動投稿・通知・村史編纂は状態遷移トランザクションの<b>外</b>で best-effort に
 * 発火し、<b>失敗しても行事の状態遷移は巻き戻さない</b>」こと。本テストは
 * {@link TimelinePostService} をモックにして例外を注入し、それでも祭が ACTIVE/ENDED へ
 * 確定しバッチが継続することを固定する。</p>
 *
 * <p><strong>現状は緑（骨格段階では発火が未配線のため副作用自体が走らない）</strong>。
 * 出陣で {@code runBatch()} のループ本体に副作用を結線した後も、この分離耐性契約が
 * <b>崩れないこと</b>を守る番人として機能する（結線を {@code @Transactional} の内側に
 * 入れてしまうと本テストが赤化して誤配線を検知する）。この意味で red 群とは役割が異なる
 * 「回帰ガード」であり、殿への報告でもその旨を明示する。</p>
 *
 * <p>受け入れ条件（設計書 §11.1/§11.3）: AC-06・AC-17b。</p>
 *
 * <p>AC-06 は {@link TimelinePostService}（自動投稿）を、AC-17b は
 * {@link VillageEventArchiveService}（村史編纂）をモックにして各副作用に例外を注入し、
 * それでも祭が ACTIVE / ENDED へ確定しバッチが継続することを固定する（出陣で本来版に更新済み）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 Wave2 ①/③ 副作用失敗の分離耐性（回帰ガード）")
class VillageEventRefluxResilienceIT extends AbstractMySqlIntegrationTest {

    @MockitoBean
    private LockProvider lockProvider;

    @MockitoBean
    private R2StorageService r2StorageService;

    /** AC-06 用: 自動投稿（timeline 越境の副作用）を例外化するためモック化する。 */
    @MockitoBean
    private TimelinePostService timelinePostService;

    /** AC-17b 用: 村史編纂（ENDED 遷移の副作用）を例外化するためモック化する。 */
    @MockitoBean
    private VillageEventArchiveService eventArchiveService;

    @Autowired
    private VillageFestivalStateTransitionBatchService festivalBatch;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageFestivalRepository festivalRepository;

    private static final Long HEADMAN_ID = 17_280_001L;

    @BeforeEach
    void setUp() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        // AC-06: 自動投稿は常に失敗する（timeline 越境の副作用の例外注入）。
        lenient().when(timelinePostService.createSystemVillagePost(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("simulated timeline reflux failure"));
        // AC-17b: 村史編纂は常に失敗する（ENDED 遷移の副作用の例外注入・試練の申し送り②の本来版）。
        lenient().doThrow(new RuntimeException("simulated archive compilation failure"))
                .when(eventArchiveService).archiveFestival(any());
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-06: timeline 副作用が例外化しても祭は ACTIVE 確定・バッチ継続
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-06 自動投稿が例外化しても SCHEDULED→ACTIVE は確定し、バッチは例外を投げず継続する")
    void timelineFailure_doesNotRollbackActivation() {
        VillageEntity v = persistVillage();
        VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1));

        assertThatCode(() -> festivalBatch.runBatch()).doesNotThrowAnyException();

        assertThat(festivalRepository.findById(f.getId())).get()
                .satisfies(reloaded ->
                        assertThat(reloaded.getStatus()).isEqualTo(VillageFestivalStatus.ACTIVE));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-17b: 村史編纂（ENDED 遷移の副作用）が例外化しても祭は ENDED 確定・バッチ継続
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-17b 村史編纂が例外化しても ACTIVE→ENDED は確定し、バッチは継続する（編纂例外の分離）")
    void archiveFailure_doesNotRollbackEnding() {
        VillageEntity v = persistVillage();
        VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(5));

        assertThatCode(() -> festivalBatch.runBatch()).doesNotThrowAnyException();

        assertThat(festivalRepository.findById(f.getId())).get()
                .satisfies(reloaded ->
                        assertThat(reloaded.getStatus()).isEqualTo(VillageFestivalStatus.ENDED));
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("rzl-" + Long.toHexString(System.nanoTime()))
                .name("耐性村" + System.nanoTime())
                .description("副作用分離耐性テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_ID)
                .build();
        return villageRepository.saveAndFlush(v);
    }

    private VillageFestivalEntity persistFestival(java.util.UUID villageId, VillageFestivalStatus status,
                                                  LocalDateTime starts, LocalDateTime ends) {
        VillageFestivalEntity f = VillageFestivalEntity.builder()
                .villageId(villageId)
                .title("祭" + System.nanoTime())
                .startsAt(starts)
                .endsAt(ends)
                .status(status)
                .createdByUserId(HEADMAN_ID)
                .build();
        return festivalRepository.saveAndFlush(f);
    }
}
