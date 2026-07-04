package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservationSlotService#updateSlot} の <b>永続化</b> 結合テスト（実 MySQL）。
 *
 * <h2>このテストが守る不変条件 / 背景（実機E2E #1665）</h2>
 * <p>{@code updateSlot} は以前 {@code slotRepository.save(entity.toBuilder().build())} で
 * <b>managed entity の detached コピー</b>を保存していた。merge の戻り値（新値）はレスポンスに
 * 乗るが、未変更の元 managed entity が同一トランザクションの flush 時に勝ち、
 * <b>DB は旧値のまま</b>残るバグがあった（approvalMode に限らず title 等の全フィールド）。</p>
 *
 * <p>既存の純 Mockito 単体テスト（{@link ReservationSlotServiceTest}）は
 * {@code ArgumentCaptor} で「save に渡った引数（＝detached コピーの新値）」を検証していたため、
 * このバグを <b>すり抜けた</b>。そこで本テストは実 JPA 永続層で
 * 「updateSlot → flush → 永続化コンテキストを clear → DB から再読込」して
 * <b>新値が DB に残ること</b>を検証し、回帰の番人とする。</p>
 *
 * <p>このテストは修正前のコード（detached コピー保存）では <b>必ず失敗</b>し、
 * managed entity を in-place 変更する修正後のコードでのみ成功する。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@Transactional
@DisplayName("ReservationSlotService.updateSlot 永続化結合テスト（実MySQL・再読込検証）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationSlotServicePersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationSlotService service;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long TEAM_ID = 7777L;
    private static final Long CREATED_BY = 8888L;
    // 実 createSlot（本番コード・実 Clock）の過去日ガード（#1680 で導入・slotDate < today は 400）を避けるため、
    // 実行時起点の十分未来の相対日付を使う（固定日付 2026-09-01 はその日付到達後に過去日扱いで落ちる地雷だった）。
    private static final LocalDate SLOT_DATE = LocalDate.now().plusMonths(1);
    private static final LocalTime START_TIME = LocalTime.of(10, 0);
    private static final LocalTime END_TIME = LocalTime.of(11, 0);

    /** スロットを 1 件作成し、flush+clear して detached 状態の ID を返す。 */
    private Long createSlot(ApprovalMode initialApprovalMode) {
        CreateSlotRequest request = new CreateSlotRequest(
                null, "作成時タイトル", SLOT_DATE, START_TIME, END_TIME,
                null, new BigDecimal("1000"), "作成時メモ", initialApprovalMode, null);
        ReservationSlotResponse created = service.createSlot(TEAM_ID, request, CREATED_BY);
        em.flush();
        em.clear();
        return created.getId();
    }

    /** updateSlot 実行後、永続化コンテキストを空にして DB から再読込する。 */
    private ReservationSlotEntity updateAndReload(Long slotId, UpdateSlotRequest request) {
        service.updateSlot(TEAM_ID, slotId, request);
        // managed entity がそのまま見えてしまわないよう flush+clear で永続化コンテキストを空にし、
        // DB から SELECT し直す（detached コピー保存バグなら旧値が返る）。
        em.flush();
        em.clear();
        return slotRepository.findByIdAndTeamId(slotId, TEAM_ID).orElseThrow();
    }

    @Nested
    @DisplayName("基本フィールドの更新が DB へ永続化される")
    class BasicFieldPersistence {

        @Test
        @DisplayName("title 変更が再読込後も残る（detached コピー保存バグの番人）")
        void title変更が再読込後も残る() {
            Long slotId = createSlot(null);

            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, "更新後タイトル", null, null, null, null, null, null, null, null);
            ReservationSlotEntity reloaded = updateAndReload(slotId, request);

            assertThat(reloaded.getTitle())
                    .as("title の更新が DB に永続化されていること")
                    .isEqualTo("更新後タイトル");
        }

        @Test
        @DisplayName("複数フィールド（メモ・価格・時間帯）の同時更新が再読込後も残る")
        void 複数フィールド更新が再読込後も残る() {
            Long slotId = createSlot(null);

            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, LocalTime.of(13, 0), LocalTime.of(15, 0),
                    new BigDecimal("2500"), "更新後メモ", null, null, null);
            ReservationSlotEntity reloaded = updateAndReload(slotId, request);

            assertThat(reloaded.getStartTime()).as("startTime 永続化").isEqualTo(LocalTime.of(13, 0));
            assertThat(reloaded.getEndTime()).as("endTime 永続化").isEqualTo(LocalTime.of(15, 0));
            assertThat(reloaded.getPrice()).as("price 永続化").isEqualByComparingTo(new BigDecimal("2500"));
            assertThat(reloaded.getNote()).as("note 永続化").isEqualTo("更新後メモ");
        }
    }

    @Nested
    @DisplayName("approvalMode の更新が DB へ永続化される")
    class ApprovalModePersistence {

        @Test
        @DisplayName("approvalMode=MANUAL 指定が再読込後も残る")
        void approvalMode上書きが再読込後も残る() {
            // 初期は継承（null）の枠を MANUAL へ上書き
            Long slotId = createSlot(null);

            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, null, null, null, null, ApprovalMode.MANUAL, null, null);
            ReservationSlotEntity reloaded = updateAndReload(slotId, request);

            assertThat(reloaded.getApprovalMode())
                    .as("approvalMode の上書きが DB に永続化されていること（#1665 の本丸）")
                    .isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("clearApprovalMode=true で上書きが解除され NULL（チーム既定継承）が再読込後も残る")
        void approvalMode解除がnullとして再読込後も残る() {
            // 初期は MANUAL で上書き済みの枠
            Long slotId = createSlot(ApprovalMode.MANUAL);

            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, null, null, null, null, null, true, null);
            ReservationSlotEntity reloaded = updateAndReload(slotId, request);

            assertThat(reloaded.getApprovalMode())
                    .as("clearApprovalMode による NULL 戻しが DB に永続化されていること")
                    .isNull();
        }

        @Test
        @DisplayName("approvalMode/clearApprovalMode 未指定なら既存の上書き値が据え置かれる")
        void approvalMode未指定は据え置きで再読込後も残る() {
            // MANUAL で上書き済みの枠を、approvalMode 非指定で title だけ更新
            Long slotId = createSlot(ApprovalMode.MANUAL);

            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, "タイトルだけ変更", null, null, null, null, null, null, null, null);
            ReservationSlotEntity reloaded = updateAndReload(slotId, request);

            assertThat(reloaded.getApprovalMode())
                    .as("未指定フィールドは据え置き（MANUAL のまま）であること")
                    .isEqualTo(ApprovalMode.MANUAL);
            assertThat(reloaded.getTitle())
                    .as("title は更新されていること")
                    .isEqualTo("タイトルだけ変更");
        }
    }
}
