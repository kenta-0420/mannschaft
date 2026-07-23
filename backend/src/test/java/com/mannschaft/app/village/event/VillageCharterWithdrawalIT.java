package com.mannschaft.app.village.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageCharterDrafterEntity;
import com.mannschaft.app.village.entity.VillageCharterEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageCharterDrafterRepository;
import com.mannschaft.app.village.repository.VillageCharterRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17.3 村憲章 — 退会匿名化の試練テスト（AC-19・red 先行・設計書 §11.1）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>試練（red）テスト</strong>。策定者ユーザーの退会（{@link UserAnonymizedEvent}）で
 * {@code village_charter_drafters} の当該行の {@code user_id} を NULL 化し、{@code nickname_snapshot}
 * は残置する処理は W1 骨格では<strong>未実装</strong>（{@code VillageUserCleanerEventListener} に
 * 憲章策定者の匿名化メソッドがまだ無い）。よって退会後も {@code user_id} が残り、
 * 「{@code user_id} が NULL 化される」を待つ本テストは red（出陣 W3 で
 * {@code anonymizeCharterDrafters} を追加して green 化）。</p>
 *
 * <p>金型 {@link VillageMeetupCapacityConcurrencyIT} と同じく <strong>@Transactional を付けない</strong>
 * （リスナーは {@code AFTER_COMMIT}＋{@code REQUIRES_NEW}＋{@code @Async} なので、コミット済みの
 * 策定者行に対して退会イベントを直接発火し、非同期反映をバウンド付きポーリングで待つ）。
 * {@link #tearDown()} で後始末する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.3 村憲章 退会匿名化テスト（試練・red・AC-19）")
class VillageCharterWithdrawalIT extends AbstractMySqlIntegrationTest {

    @Autowired private VillageUserCleanerEventListener listener;
    @Autowired private VillageRepository villageRepository;
    @Autowired private VillageCharterRepository charterRepository;
    @Autowired private VillageCharterDrafterRepository drafterRepository;

    @MockitoBean private R2StorageService r2StorageService;

    private static final Long DRAFTER_USER_ID = 17_319_001L;

    private UUID villageId;
    private UUID charterId;
    private UUID drafterId;

    @AfterEach
    void tearDown() {
        if (charterId != null) {
            drafterRepository.findByCharterIdOrderBySortOrderAsc(charterId).forEach(drafterRepository::delete);
            charterRepository.findById(charterId).ifPresent(charterRepository::delete);
        }
        if (villageId != null) {
            villageRepository.findById(villageId).ifPresent(villageRepository::delete);
        }
    }

    @Test
    @DisplayName("AC-19 策定者ユーザーの退会でuser_idはNULL化されるがnickname_snapshotは残置（実名は元々保存されない）")
    void withdrawal_nullsUserId_keepsNicknameSnapshot_AC19() throws Exception {
        // ── セットアップ（実コミット）──────────────────────────────────
        VillageEntity v = villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("chwd-" + Long.toHexString(System.nanoTime()))
                .name("退会憲章村" + System.nanoTime())
                .description("村憲章退会テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(DRAFTER_USER_ID)
                .build());
        villageId = v.getId();

        VillageCharterEntity c = charterRepository.saveAndFlush(VillageCharterEntity.builder()
                .villageId(villageId)
                .enactedAt(LocalDateTime.now())
                .build());
        charterId = c.getId();

        VillageCharterDrafterEntity d = drafterRepository.saveAndFlush(VillageCharterDrafterEntity.builder()
                .charterId(charterId)
                .userId(DRAFTER_USER_ID)
                .nicknameSnapshot("村の開祖（仮名）")
                .sortOrder(0)
                .build());
        drafterId = d.getId();

        // ── 退会イベントを発火（リスナー直呼び・AFTER_COMMIT/@Async を非同期実行）───────
        listener.handleUserAnonymized(new UserAnonymizedEvent(DRAFTER_USER_ID, "x@example.com"));

        // 非同期反映をバウンド付きで待つ（W1 は永遠に NULL 化されないためタイムアウト＝red）。
        boolean nulled = awaitUserIdNulled(drafterId);

        assertThat(nulled)
                .as("退会で策定者の user_id が NULL 化されるべし（AC-19）")
                .isTrue();

        // nickname_snapshot は残置（仮名史料・実名は元々保存されない・§10 G4）。
        VillageCharterDrafterEntity refreshed = drafterRepository.findById(drafterId).orElseThrow();
        assertThat(refreshed.getUserId()).as("user_id は NULL 化されるべし").isNull();
        assertThat(refreshed.getNicknameSnapshot())
                .as("nickname_snapshot（仮名）は退会後も残置されるべし").isEqualTo("村の開祖（仮名）");
    }

    /** {@code user_id} が NULL 化されるのを最大 ~6 秒バウンドで待つ（非同期反映）。 */
    private boolean awaitUserIdNulled(UUID id) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            VillageCharterDrafterEntity d = drafterRepository.findById(id).orElse(null);
            if (d != null && d.getUserId() == null) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
