package com.mannschaft.app.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可番人「裏目付」第二陣・部隊C-1 — reservation ドメイン認可契約テスト（動的固定）。
 *
 * <p><b>目的</b>: 予約ドメインの IDOR 面を持つ読取/書込 EP について、認可が
 * <b>実 HTTP + 実 MySQL 経路で本当にゲートしているか</b>を固定する。既存の
 * {@code ReservationAuthorizationEnforcementTest} は mock スライスで「@PreAuthorize が
 * 非管理者に 403 を返す配線」だけを見るが、<b>越境した reservationId で他チームのデータが
 * 実際に読めてしまわないか</b>（BOLA / テナント境界）は見ていない。裏目付が狙う穴は
 * まさにここ（白名簿を呼ぶかではなく、他テナントのデータを隠すか）。</p>
 *
 * <p><b>認可モデル（reservation ドメイン）:</b></p>
 * <ul>
 *   <li><b>管理 EP</b>（一覧・確定・キャンセル(管理者)・完了・no-show・リスケ・管理メモ・統計・
 *       リマインダー）: {@code @PreAuthorize("@accessGuard.isScopeAdmin(#teamId,'TEAM')")}。
 *       非管理者は 403。別チーム管理者が当該 URL を叩いても（自身が当該チームの管理者でないため）403。</li>
 *   <li><b>予約詳細 GET</b>: Service 層の所有者-or-管理者ゲート
 *       （{@code ReservationService#getReservation}）。他チームの reservationId は
 *       {@code findByIdAndTeamId} で 400（存在秘匿）。同一チーム内でも他人の予約は 403
 *       （{@code RESERVATION_PERMISSION_DENIED}）。</li>
 *   <li><b>予約作成 POST</b>: {@code ReservationViewAccessGuard}（会員 or 公開）。
 *       非会員は 403。</li>
 *   <li><b>マイ予約キャンセル POST /reservations/{id}/cancel</b>: 所有権ゲート
 *       （{@code findByIdAndUserId}）。他人の予約は 400。</li>
 * </ul>
 *
 * <p><b>本テストが炙り出した実穴（根治済み）:</b> リマインダー系 2 EP
 * （{@code GET/POST .../{reservationId}/reminders}）は {@code @PreAuthorize} が
 * {@code #teamId} の管理者性しか見ず、下流の {@code ReservationReminderService} へ
 * {@code reservationId} のみを渡していた。よって「チームAの正規管理者が、チームBの
 * reservationId を URL {@code /teams/{teamA}/reservations/{teamBの予約}/reminders} に
 * 差し込む」と、チームBの予約のリマインダーを読み書きできる<b>テナント越境 BOLA</b> が成立していた。
 * {@code TeamReservationController} に {@code reservationService.assertReservationInTeam(teamId, reservationId)}
 * を敷設し、reservationId が当該チームに属さなければ 400（存在秘匿）に畳み込んで封じた。</p>
 *
 * <p><b>2026-08-11 是正（ErrorCodeHttpStatusDeclarationGuardTest ロットA）:</b> 従来は
 * reservation ドメインの {@code *_NOT_FOUND} 系（{@code RESERVATION_003}/{@code 017} 等）が
 * {@code ERROR_CODE_STATUS_MAP} 未登録のため {@code Severity.WARN} 既定の HTTP 400 に収束していた
 * （越境も真の不在も一律 400 で存在は秘匿できていたが、404 を使う他ドメインとステータスの流儀が
 * 割れていた）。#2468 として保留されていたこの乖離を本是正で解消し、他ドメインの
 * {@code *_NOT_FOUND} 系と同じ 404 に統一した（下記テストはこの是正後の実挙動を固定する）。</p>
 *
 * <p>金型: {@code MemberScopeContractIT} / {@code ChatChannelAccessScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。</p>
 *
 * <p><b>Wave7 追補（営業時間/設定/ブロック時間一覧/ライン一覧/スロット一覧・詳細）:</b>
 * 認可番人 Wave7 で「認可シグナルが一切見つからない」EP として摘出された 7 件
 * （{@code ReservationBusinessHourController#getBusinessHours/getSettings/listBlockedTimes}・
 * {@code TeamReservationLineController#listLines}・
 * {@code TeamReservationSlotController#getSlot/listSlots/listAvailableSlots}）に
 * {@code ReservationViewAccessGuard#assertCanView}（予約作成・グリッド・メニュー一覧と同一述語＝
 * 会員 or 公開）を敷設した（§7〜9）。同一チーム内でも view ゲートは ADMIN/非 ADMIN を区別しない
 * （会員なら誰でも閲覧可）ため、象限は「非会員→403」「別チーム ADMIN（BOLA）→403」
 * 「非 ADMIN メンバー→200」「正当 ADMIN→200（非回帰）」の 4 点。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("reservation ドメイン 認可契約テスト（裏目付C・スコープ越境の403/400固定）")
class ReservationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @Autowired
    private ReservationLineRepository lineRepository;

    @Autowired
    private ReservationReminderRepository reminderRepository;

    @Autowired
    private ReservationWaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private EmergencyClosureRepository emergencyClosureRepository;

    @Autowired
    private EmergencyClosureConfirmationRepository emergencyClosureConfirmationRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    /** teamA の ADMIN（正当な管理者）。 */
    private Long adminAId;
    /** teamA の非管理者メンバー（管理 EP には 403・予約詳細では他人の予約に 403）。 */
    private Long memberAId;
    /** teamA の一般会員で reservationA の所有者。 */
    private Long ownerAId;
    /** teamB の ADMIN（越境攻撃者。teamA の URL には 403）。 */
    private Long adminBId;
    /** どこにも所属しない完全な部外者。 */
    private Long outsiderId;

    /** teamA の予約（所有者 = ownerAId・PENDING・未来枠）。 */
    private Long reservationAId;
    /** teamB の予約（越境 reservationId として teamA の URL に差し込む主役）。 */
    private Long reservationBId;
    /** teamA の未来枠 ID（作成 EP のリクエストに用いる）。 */
    private Long slotAId;
    /** teamA のライン ID（作成 EP のリクエストに用いる）。 */
    private Long lineAId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("RSVAUTHZ チームA");
        teamBId = insertTeam("RSVAUTHZ チームB");

        adminAId = insertUser("rsvauthz-admin-a@example.com");
        memberAId = insertUser("rsvauthz-member-a@example.com");
        ownerAId = insertUser("rsvauthz-owner-a@example.com");
        adminBId = insertUser("rsvauthz-admin-b@example.com");
        outsiderId = insertUser("rsvauthz-outsider@example.com");

        // isScopeAdmin（user_roles）と isMember（memberships）は別系統のため、
        // ADMIN にも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, ownerAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        // outsiderId はどこにも所属させない。

        // 未来枠（cancel_deadline 既定 24h より十分先）を各チームに用意する。
        LocalDate futureDate = LocalDate.now().plusDays(30);
        ReservationLineEntity lineA = lineRepository.save(ReservationLineEntity.builder()
                .teamId(teamAId).name("一般").build());
        ReservationLineEntity lineB = lineRepository.save(ReservationLineEntity.builder()
                .teamId(teamBId).name("一般").build());
        ReservationSlotEntity slotA = slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamAId).slotDate(futureDate)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .build());
        ReservationSlotEntity slotB = slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamBId).slotDate(futureDate)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .build());
        slotAId = slotA.getId();
        lineAId = lineA.getId();

        reservationAId = reservationRepository.save(ReservationEntity.builder()
                .teamId(teamAId).userId(ownerAId)
                .reservationSlotId(slotA.getId()).lineId(lineA.getId())
                .status(ReservationStatus.PENDING)
                .build()).getId();
        reservationBId = reservationRepository.save(ReservationEntity.builder()
                .teamId(teamBId).userId(adminBId)
                .reservationSlotId(slotB.getId()).lineId(lineB.getId())
                .status(ReservationStatus.PENDING)
                .build()).getId();

        // reservationA に既存リマインダーを 1 件（一覧の非回帰確認用）。
        reminderRepository.save(ReservationReminderEntity.builder()
                .reservationId(reservationAId)
                .remindAt(LocalDateTime.now().plusDays(29))
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/reservations（一覧・管理ゲート isScopeAdmin）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/reservations（一覧）")
    class ListReservations {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非管理者メンバーは403（管理ゲート発火）")
        void 非管理者メンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は403")
        void 別チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /teams/{teamId}/reservations/{id}（詳細・所有者 or 管理者ゲート）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /teams/{teamId}/reservations/{id}（詳細）")
    class GetReservation {

        @Test
        @DisplayName("別チームの予約IDを当該チームURLに差し込むと400（BOLA存在秘匿）")
        void 越境予約IDは400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}", teamAId, reservationBId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("同一チームでも他人の予約は403（会員が他人の予約を覗けない）")
        void 同一チーム他人の予約は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}", teamAId, reservationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("所有者は自分の予約詳細を200で取得")
        void 所有者は200() throws Exception {
            setAuth(ownerAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}", teamAId, reservationAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("管理者は他人の予約詳細も200で取得（非回帰）")
        void 管理者は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}", teamAId, reservationAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /teams/{teamId}/reservations（作成・会員 or 公開ゲート）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /teams/{teamId}/reservations（作成）")
    class CreateReservation {

        @Test
        @DisplayName("非会員（部外者）の予約作成は403")
        void 非会員は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("会員の予約作成は201（非回帰）")
        void 会員は201() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reservationSlotId", slotAId);
            body.put("lineId", lineAId);
            body.put("userNote", "裏目付テスト");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 管理状態遷移（confirm / cancel(管理者) / admin-note）— isScopeAdmin + 帰属
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 管理状態遷移（confirm / cancel / admin-note）")
    class AdminStateTransitions {

        @Test
        @DisplayName("非管理者メンバーの確定は403")
        void 非管理者の確定は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/confirm", teamAId, reservationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMINが当該チームURLで確定しようとしても403")
        void 別チームADMINの確定は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/confirm", teamAId, reservationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINが別チームの予約IDを差し込むと400（帰属検証）")
        void 越境予約IDの確定は400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/confirm", teamAId, reservationBId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("正当ADMINの確定は200（非回帰）")
        void 正当ADMINの確定は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/confirm", teamAId, reservationAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINの管理者キャンセルは200（非回帰）")
        void 正当ADMINのキャンセルは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/cancel", teamAId, reservationAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("reason", "管理者都合"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別チームADMINの越境キャンセルは403")
        void 別チームADMINのキャンセルは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/cancel", teamAId, reservationAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("reason", "乗っ取り"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINが別チーム予約に管理メモを付けようとすると400")
        void 越境予約IDの管理メモは400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/reservations/{id}/admin-note", teamAId, reservationBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "越境メモ"))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST /reservations/{id}/cancel（マイ予約キャンセル・所有権ゲート）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /reservations/{id}/cancel（マイ予約キャンセル）")
    class CancelMyReservation {

        @Test
        @DisplayName("他人が他人の予約をキャンセルしようとすると400（所有権ゲート）")
        void 他人のキャンセルは400() throws Exception {
            setAuth(memberAId); // ownerAId ではない同一チーム会員
            mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("reason", "横取り"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("部外者が他人の予約をキャンセルしようとしても400")
        void 部外者のキャンセルは400() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("reason", "横取り"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("所有者は自分の予約をキャンセルできる200（非回帰）")
        void 所有者のキャンセルは200() throws Exception {
            setAuth(ownerAId);
            mockMvc.perform(post("/api/v1/reservations/{id}/cancel", reservationAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("reason", "都合により"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. リマインダー（GET/POST .../{id}/reminders）— 越境BOLA根治の主役
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. リマインダー（越境BOLA根治）")
    class Reminders {

        @Test
        @DisplayName("正当ADMINが別チームの予約IDでリマインダー一覧を読もうとすると400（越境BOLA封鎖）")
        void 越境リマインダー一覧は400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}/reminders", teamAId, reservationBId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("正当ADMINが別チームの予約IDにリマインダーを書き込もうとすると400（越境BOLA封鎖）")
        void 越境リマインダー作成は400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/reminders", teamAId, reservationBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reminderBody())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("別チームADMINは当該チームURLのリマインダー一覧に403")
        void 別チームADMINのリマインダー一覧は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}/reminders", teamAId, reservationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非管理者メンバーはリマインダー一覧に403")
        void 非管理者のリマインダー一覧は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}/reminders", teamAId, reservationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは自チーム予約のリマインダー一覧を200で取得（非回帰）")
        void 正当ADMINのリマインダー一覧は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservations/{id}/reminders", teamAId, reservationAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは自チーム予約にリマインダーを201で作成（非回帰）")
        void 正当ADMINのリマインダー作成は201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/reservations/{id}/reminders", teamAId, reservationAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reminderBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> reminderBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("remindAt", LocalDateTime.now().plusDays(20).toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. 予約設定閲覧（GET business-hours / GET (settings) / GET blocked-times）
    //    — Service 層 ReservationViewAccessGuard（会員 or 公開）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. 予約設定閲覧（営業時間・設定・ブロック時間一覧）")
    class BusinessHourSettingsView {

        private final LocalDate from = LocalDate.now();
        private final LocalDate to = LocalDate.now().plusDays(60);

        @Test
        @DisplayName("部外者は営業時間取得に403")
        void 部外者は営業時間403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/business-hours", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は営業時間取得に403")
        void 別チームADMINは営業時間403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/business-hours", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは営業時間取得を200で取得")
        void 非ADMINメンバーは営業時間200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/business-hours", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは営業時間取得を200で取得（非回帰）")
        void 正当ADMINは営業時間200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/business-hours", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("部外者は予約設定取得に403")
        void 部外者は予約設定403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は予約設定取得に403")
        void 別チームADMINは予約設定403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは予約設定取得を200で取得")
        void 非ADMINメンバーは予約設定200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは予約設定取得を200で取得（非回帰）")
        void 正当ADMINは予約設定200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("部外者はブロック時間一覧に403")
        void 部外者はブロック時間403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/blocked-times", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）はブロック時間一覧に403")
        void 別チームADMINはブロック時間403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/blocked-times", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーはブロック時間一覧を200で取得")
        void 非ADMINメンバーはブロック時間200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/blocked-times", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINはブロック時間一覧を200で取得（非回帰）")
        void 正当ADMINはブロック時間200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-settings/blocked-times", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 予約ライン一覧閲覧（GET /reservation-lines）
    //    — Service 層 ReservationViewAccessGuard（会員 or 公開）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. 予約ライン一覧閲覧")
    class ReservationLineView {

        @Test
        @DisplayName("部外者はライン一覧に403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-lines", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）はライン一覧に403")
        void 別チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-lines", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーはライン一覧を200で取得")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-lines", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINはライン一覧を200で取得（非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-lines", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. 予約スロット閲覧（GET 一覧 / GET available / GET 詳細）
    //    — Service 層 ReservationViewAccessGuard（会員 or 公開）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. 予約スロット閲覧（一覧・空き一覧・詳細）")
    class ReservationSlotView {

        private final LocalDate from = LocalDate.now();
        private final LocalDate to = LocalDate.now().plusDays(60);

        @Test
        @DisplayName("部外者はスロット一覧に403")
        void 部外者はスロット一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）はスロット一覧に403")
        void 別チームADMINはスロット一覧403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーはスロット一覧を200で取得")
        void 非ADMINメンバーはスロット一覧200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINはスロット一覧を200で取得（非回帰）")
        void 正当ADMINはスロット一覧200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("部外者は空きスロット一覧に403")
        void 部外者は空きスロット一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/available", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は空きスロット一覧に403")
        void 別チームADMINは空きスロット一覧403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/available", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは空きスロット一覧を200で取得")
        void 非ADMINメンバーは空きスロット一覧200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/available", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは空きスロット一覧を200で取得（非回帰）")
        void 正当ADMINは空きスロット一覧200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/available", teamAId)
                            .param("from", from.toString())
                            .param("to", to.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("部外者はスロット詳細に403")
        void 部外者はスロット詳細403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/{slotId}", teamAId, slotAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）はスロット詳細に403")
        void 別チームADMINはスロット詳細403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/{slotId}", teamAId, slotAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーはスロット詳細を200で取得")
        void 非ADMINメンバーはスロット詳細200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/{slotId}", teamAId, slotAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINはスロット詳細を200で取得（非回帰）")
        void 正当ADMINはスロット詳細200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/reservation-slots/{slotId}", teamAId, slotAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. 自己スコープ EP（AuthorizedInService 監査済・非回帰確認）
    //     — リポジトリクエリが呼び出しユーザー自身の ID に固定される構造的な自己スコープ EP
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. 自己スコープEP（AuthorizedInService監査済）")
    class SelfScopedEndpoints {

        @Test
        @DisplayName("GET /users/me/reservation-waitlist は呼び出しユーザー自身のWAITINGのみ返す")
        void マイキャンセル待ち一覧は自己スコープ() throws Exception {
            waitlistEntryRepository.save(ReservationWaitlistEntryEntity.builder()
                    .teamId(teamAId).slotId(slotAId).userId(ownerAId).status(WaitlistStatus.WAITING).build());
            waitlistEntryRepository.save(ReservationWaitlistEntryEntity.builder()
                    .teamId(teamAId).slotId(slotAId).userId(memberAId).status(WaitlistStatus.WAITING).build());
            em.flush();
            em.clear();

            setAuth(ownerAId);
            mockMvc.perform(get("/api/v1/users/me/reservation-waitlist"))
                    .andExpect(status().isOk())
                    // 件数1件（memberAId分が漏れていない）で自己スコープを固定する。
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("GET /reservations/my は呼び出しユーザー自身の予約のみ返す（同一チームの他人の予約は見えない）")
        void マイ予約一覧は自己スコープ() throws Exception {
            setAuth(ownerAId);
            mockMvc.perform(get("/api/v1/reservations/my"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(reservationAId));

            // 同一チームの別会員（予約なし）は自分の予約が無いため空配列（他人の予約は漏れない）。
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/reservations/my"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("GET /reservations/upcoming は呼び出しユーザー自身の直近予約のみ返す")
        void マイ直近予約一覧は自己スコープ() throws Exception {
            // findUpcomingByUserId は CONFIRMED のみ対象のため、専用の CONFIRMED 予約を用意する
            // （reservationAId は他セクション同様 PENDING のため対象外）。
            ReservationSlotEntity slot = slotRepository.save(ReservationSlotEntity.builder()
                    .teamId(teamAId).slotDate(LocalDate.now().plusDays(35))
                    .startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(15, 0))
                    .build());
            reservationRepository.save(ReservationEntity.builder()
                    .teamId(teamAId).userId(ownerAId)
                    .reservationSlotId(slot.getId()).lineId(lineAId)
                    .status(ReservationStatus.CONFIRMED)
                    .build());
            em.flush();
            em.clear();

            setAuth(ownerAId);
            mockMvc.perform(get("/api/v1/reservations/upcoming"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));

            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/reservations/upcoming"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("DELETE .../waitlist は他人のWAITINGを取消できず404（本人のみ204）")
        void キャンセル待ち取消は自己スコープ() throws Exception {
            waitlistEntryRepository.save(ReservationWaitlistEntryEntity.builder()
                    .teamId(teamAId).slotId(slotAId).userId(ownerAId).status(WaitlistStatus.WAITING).build());
            em.flush();
            em.clear();

            // 同一チームの別会員は自分のWAITINGが無いため404（他人のWAITINGを取消せない）。
            setAuth(memberAId);
            mockMvc.perform(delete(
                            "/api/v1/teams/{teamId}/reservation-slots/{slotId}/waitlist", teamAId, slotAId))
                    .andExpect(status().isNotFound());

            // 本人は自分のWAITINGを取消できる（非回帰）。
            setAuth(ownerAId);
            mockMvc.perform(delete(
                            "/api/v1/teams/{teamId}/reservation-slots/{slotId}/waitlist", teamAId, slotAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("POST .../emergency-closures/{id}/confirm は他人の確認レコードを操作できず400（本人のみ200）")
        void 臨時休業確認は自己スコープ() throws Exception {
            Long closureId = emergencyClosureRepository.save(EmergencyClosureEntity.builder()
                    .teamId(teamAId)
                    .startDate(LocalDate.now().plusDays(30)).endDate(LocalDate.now().plusDays(30))
                    .reason("研修").subject("休業のお知らせ").messageBody("休業します")
                    .createdBy(adminAId)
                    .build()).getId();
            emergencyClosureConfirmationRepository.save(EmergencyClosureConfirmationEntity.builder()
                    .emergencyClosureId(closureId).userId(ownerAId).reservationId(reservationAId)
                    .appointmentAt(LocalDateTime.now().plusDays(30))
                    .build());
            em.flush();
            em.clear();

            // 同一チームの別会員は自分の確認レコードが無いため400（他人の確認レコードを操作できない）。
            setAuth(memberAId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/emergency-closures/{closureId}/confirm", teamAId, closureId))
                    .andExpect(status().isBadRequest());

            // 本人は自分の確認レコードを確認済みにできる（非回帰）。
            setAuth(ownerAId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/emergency-closures/{closureId}/confirm", teamAId, closureId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'RSVAUTHZ', 'テスト', 'RSVAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('rsv-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
