package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.PenaltyApplyScope;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentPenaltyRecomputeBatch} の単体テスト。
 * F03.11 Phase5b ペナルティ再計算バッチの主要パスを検証する。
 *
 * <p>本テストは、閾値を下回って解除された行が絞り込みから外れて母集合が
 * 縮んでいく状況でも、キーセットページングにより全アクティブペナルティが
 * 取りこぼしなく再判定されることを守る番人である。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentPenaltyRecomputeBatch 単体テスト")
class RecruitmentPenaltyRecomputeBatchTest {

    @Mock
    private RecruitmentUserPenaltyRepository penaltyRepository;

    @Mock
    private RecruitmentPenaltySettingRepository settingRepository;

    @Mock
    private RecruitmentNoShowRecordRepository noShowRepository;

    @InjectMocks
    private RecruitmentPenaltyRecomputeBatch batch;

    @Nested
    @DisplayName("recomputePenalties - バッチメインループ")
    class RecomputePenalties {

        @Test
        @DisplayName("アクティブペナルティが0件 → 何もしない")
        void recomputePenalties_noActivePenalties_doesNothing() {
            // given
            given(penaltyRepository.findActivePenaltiesAfterId(any(), anyLong(), any(Pageable.class)))
                    .willReturn(Collections.emptyList());

            // when
            batch.recomputePenalties();

            // then
            verify(penaltyRepository, never()).saveAll(any());
        }

        /**
         * 取りこぼし検出テスト（キーセット化の中核 AC）。
         *
         * <p>チャンクサイズ(500)を超える件数を用意する余裕は無いためチャンクサイズを
         * 小さく捉えて検証する代わりに、実運用同様のチャンクサイズ(500)未満で
         * 「一部の行だけが解除されて絞り込みから外れる」状況をインメモリ Fake
         * ({@link FakePenaltyStore}) で再現し、全対象行が再判定されることを実証する。</p>
         *
         * <p>解除条件: 設定が無効化されている（{@code enabled=false}）→ 即解除。
         * 偶数IDのペナルティは無効化された設定に紐付けており、1回の走査で
         * {@code liftedAt} がセットされて絞り込みから外れる。旧実装（OFFSET を
         * 「取得件数ぶん進める」方式）だと、この脱落によって後続ページの一部が
         * 永久に読み飛ばされる。</p>
         */
        @Test
        @DisplayName("一部の行が解除されて絞り込みから外れても全件が再判定される（キーセット方式）")
        void recomputePenalties_shrinkingFilter_processesAllPenaltiesWithoutLoss() {
            // given: 40件のアクティブペナルティ。偶数IDは無効化された設定に紐付け（即解除対象）、
            // 奇数IDは有効な設定だが閾値以上のNO_SHOWが残っており解除されない。
            int totalCount = 40;

            RecruitmentPenaltySettingEntity disabledSetting = buildSetting(1L, false);
            RecruitmentPenaltySettingEntity enabledSetting = buildSetting(2L, true);

            List<RecruitmentUserPenaltyEntity> allPenalties = new ArrayList<>();
            for (long id = 1; id <= totalCount; id++) {
                boolean useDisabledSetting = (id % 2 == 0);
                Long settingId = useDisabledSetting ? disabledSetting.getId() : enabledSetting.getId();
                allPenalties.add(buildPenalty(id, settingId));
            }

            given(settingRepository.findById(disabledSetting.getId())).willReturn(Optional.of(disabledSetting));
            given(settingRepository.findById(enabledSetting.getId())).willReturn(Optional.of(enabledSetting));
            // 奇数ID側（有効設定）は閾値を下回らない前提（解除されない）
            given(noShowRepository.countConfirmedNoShows(any(), any())).willReturn(99L);

            FakePenaltyStore store = new FakePenaltyStore(allPenalties);
            given(penaltyRepository.findActivePenaltiesAfterId(any(), any(Long.class), any(Pageable.class)))
                    .willAnswer(invocation -> {
                        long cursor = invocation.getArgument(1);
                        Pageable pageable = invocation.getArgument(2);
                        return store.findByCursor(cursor, pageable.getPageSize());
                    });
            given(penaltyRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

            // when
            batch.recomputePenalties();

            // then: 40件全てが最低1回は再判定のため走査されている（取りこぼしゼロ）
            assertThat(store.scannedIds())
                    .as("全アクティブペナルティが取りこぼしなく再判定されること")
                    .containsExactlyInAnyOrderElementsOf(
                            allPenalties.stream().map(RecruitmentUserPenaltyEntity::getId).collect(Collectors.toList()));
            assertThat(store.scannedIds()).hasSize(totalCount);

            // 偶数ID(無効設定紐付け)は全て解除されている
            long revokedCount = allPenalties.stream().filter(p -> p.getId() % 2 == 0).filter(p -> p.getLiftedAt() != null).count();
            assertThat(revokedCount).isEqualTo(totalCount / 2);
        }
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

    private RecruitmentPenaltySettingEntity buildSetting(Long id, boolean enabled) {
        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .build();
        setting.update(enabled, 3, 180, 30, PenaltyApplyScope.THIS_SCOPE_ONLY, false, 14);
        setField(setting, "id", id);
        return setting;
    }

    private RecruitmentUserPenaltyEntity buildPenalty(Long id, Long settingId) {
        LocalDateTime now = LocalDateTime.now();
        RecruitmentUserPenaltyEntity penalty = RecruitmentUserPenaltyEntity.builder()
                .userId(id)
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .triggeredBySettingId(settingId)
                .triggeredNoShowCount(3)
                .startedAt(now)
                .expiresAt(now.plusDays(30))
                .build();
        setField(penalty, "id", id);
        return penalty;
    }

    private void setField(Object entity, String name, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                // テストフィクスチャ構築の失敗は原因を保持したまま送出する（握り潰さない）
                throw new IllegalStateException("failed to set field: " + name, e);
            }
        }
        throw new IllegalStateException("field not found: " + name);
    }

    /**
     * DB のキーセットフィルタ（{@code id > cursor AND liftedAt IS NULL AND expiresAt > now}）と
     * 同じ意味論をインメモリで再現する Fake ストア。
     */
    private static class FakePenaltyStore {
        private final List<RecruitmentUserPenaltyEntity> penalties;
        private final List<Long> scannedIds = new ArrayList<>();

        FakePenaltyStore(List<RecruitmentUserPenaltyEntity> penalties) {
            this.penalties = penalties;
        }

        List<RecruitmentUserPenaltyEntity> findByCursor(long cursor, int pageSize) {
            return penalties.stream()
                    .filter(p -> p.getId() > cursor)
                    .filter(p -> p.getLiftedAt() == null)
                    .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                    .limit(pageSize)
                    .peek(p -> scannedIds.add(p.getId()))
                    .collect(Collectors.toList());
        }

        List<Long> scannedIds() {
            return scannedIds;
        }
    }
}
