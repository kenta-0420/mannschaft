package com.mannschaft.app.seal;

import com.mannschaft.app.seal.dto.CreateSealRequest;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.StampRequest;
import com.mannschaft.app.seal.repository.ElectronicSealRepository;
import com.mannschaft.app.seal.repository.SealStampLogRepository;
import com.mannschaft.app.seal.service.SealService;
import com.mannschaft.app.seal.service.SealStampService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 印鑑削除後の同一variant再作成に関する結合テスト（実DB・Testcontainers MySQL）。
 *
 * <p>{@code electronic_seals} は UNIQUE KEY {@code uk_electronic_seals_user_variant} (user_id, variant)
 * を持ち、削除は {@code deleted_at} による論理削除（{@code @SQLRestriction("deleted_at IS NULL")}）のため、
 * ソフトデリート済み行が物理的に残ったまま同一 (user_id, variant) で INSERT すると
 * {@code DataIntegrityViolationException}（→ {@code GlobalExceptionHandler} の catch-all で
 * COMMON_999/500）になる。この物理制約違反は Mockito 単体テスト（{@code SealServiceTest}）では
 * 再現できないため、実 DB 結合テストとして起票する。</p>
 */
@DisplayName("SealService 印鑑削除後の同一variant再作成 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SealServiceRecreateIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private SealService sealService;

    @Autowired
    private SealStampService sealStampService;

    @Autowired
    private ElectronicSealRepository sealRepository;

    @Autowired
    private SealStampLogRepository stampLogRepository;

    private static final Long USER_ID = 9001L;

    @Test
    @Transactional
    @DisplayName("削除済みと同一variantの印鑑再作成が成功する（500にならない）")
    void 削除済みと同一variantの印鑑再作成が成功する() {
        // Given: LAST_NAME variant の印鑑を作成して削除する（論理削除＝deleted_atセットのみ、行は物理に残る）
        SealResponse created = sealService.createSeal(USER_ID, new CreateSealRequest("LAST_NAME", "山田"));
        sealService.deleteSeal(USER_ID, created.getSealId());

        // When: 同一 variant で再作成する（修正前は UNIQUE 制約違反で 500 だった）
        SealResponse recreated = sealService.createSeal(USER_ID, new CreateSealRequest("LAST_NAME", "山田太郎"));

        // Then
        assertThat(recreated).isNotNull();
        assertThat(recreated.getVariant()).isEqualTo("LAST_NAME");
        assertThat(recreated.getDisplayText()).isEqualTo("山田太郎");
        assertThat(sealRepository.existsByUserIdAndVariant(USER_ID, SealVariant.LAST_NAME)).isTrue();

        // revive方式のため物理削除+INSERTではなく同一行のUPDATEになり、sealIdが維持される
        assertThat(recreated.getSealId()).isEqualTo(created.getSealId());
        assertThat(recreated.getGenerationVersion()).isEqualTo(created.getGenerationVersion() + 1);
    }

    @Test
    @Transactional
    @DisplayName("複数回の削除→再作成でも都度成功する")
    void 複数回の削除再作成でも成功する() {
        SealResponse seal = sealService.createSeal(USER_ID, new CreateSealRequest("FULL_NAME", "山田太郎"));

        for (int i = 0; i < 3; i++) {
            sealService.deleteSeal(USER_ID, seal.getSealId());
            seal = sealService.createSeal(USER_ID, new CreateSealRequest("FULL_NAME", "山田太郎" + i));
        }

        assertThat(seal.getDisplayText()).isEqualTo("山田太郎2");
        assertThat(sealRepository.existsByUserIdAndVariant(USER_ID, SealVariant.FULL_NAME)).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("押印実績のある印鑑を削除→再作成しても押印ログのFK(RESTRICT)に阻まれず成功し、旧ログも維持される")
    void 押印実績のある印鑑を削除再作成しても押印ログが維持される() {
        // Given: FIRST_NAME variant の印鑑を作成し、押印を1件実行する
        SealResponse seal = sealService.createSeal(USER_ID, new CreateSealRequest("FIRST_NAME", "太郎"));
        var stampLog = sealStampService.stamp(USER_ID, new StampRequest(seal.getSealId(), "CHART", 1L, null));

        // When: 印鑑を削除して同一 variant で再作成する
        // (物理削除+INSERTの方式だと seal_stamp_logs.fk_seal_stamp_logs_seal(ON DELETE RESTRICT) に阻まれて
        //  DataIntegrityViolationException になるため、revive方式で回避できることを検証する)
        sealService.deleteSeal(USER_ID, seal.getSealId());
        SealResponse recreated = sealService.createSeal(USER_ID, new CreateSealRequest("FIRST_NAME", "太郎2"));

        // Then: 再作成は成功し、かつ復活のため sealId が維持され、旧押印ログの参照整合性も壊れない
        assertThat(recreated).isNotNull();
        assertThat(recreated.getSealId()).isEqualTo(seal.getSealId());
        assertThat(stampLogRepository.findById(stampLog.getId())).isPresent();
        assertThat(stampLogRepository.findById(stampLog.getId()).get().getSealId()).isEqualTo(seal.getSealId());
    }
}
