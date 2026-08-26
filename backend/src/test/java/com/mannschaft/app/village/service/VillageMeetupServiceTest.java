package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MeetupCandidateDateAddRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateInput;
import com.mannschaft.app.village.dto.MeetupCandidateDateResponse;
import com.mannschaft.app.village.dto.MeetupConfirmRequest;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.MeetupResponse;
import com.mannschaft.app.village.dto.MeetupUpdateRequest;
import com.mannschaft.app.village.dto.MeetupVoteRequest;
import com.mannschaft.app.village.dto.MeetupVoteSummaryResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMeetupCandidateDateEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMeetupVoteEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupVoteType;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMeetupAttendanceRepository;
import com.mannschaft.app.village.repository.VillageMeetupCandidateDateRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMeetupVoteRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageMeetupService} 単体テスト（F17.1 Phase 3-β）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>create: 正常系 / 村人でない / 候補日空 / 重複日付</li>
 *   <li>update: 正常系 / 幹事以外 → 403 / CONFIRMED → 409</li>
 *   <li>cancel: 正常系 / CONFIRMED → 409</li>
 *   <li>confirm: 正常系 / 既に CONFIRMED → 409 / 他寄合の候補日 → 404</li>
 *   <li>castVote: 正常系（新規） / 再投票（UPDATE） / 村人でない</li>
 *   <li>getVoteSummary: 投票集計</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageMeetupService 単体テスト")
class VillageMeetupServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000801");
    private static final UUID OTHER_VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000802");
    private static final UUID MEETUP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000901");
    private static final UUID CANDIDATE_DATE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000a01");
    private static final UUID OTHER_CANDIDATE_DATE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000a02");
    private static final Long ACTOR_USER_ID = 901L;
    private static final Long OTHER_USER_ID = 902L;

    @Mock
    private VillageMeetupRepository meetupRepository;
    @Mock
    private VillageMeetupCandidateDateRepository candidateDateRepository;
    @Mock
    private VillageMeetupVoteRepository voteRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    /** F17.2 追補: buildResponseWithCandidates が GOING 数集計に使う（既定戻り 0）。 */
    @Mock
    private VillageMeetupAttendanceRepository attendanceRepository;
    @Mock
    private AuditLogService auditLogService;
    /** F17.2 Wave2 ①: 行事作成・確定の還流イベント発行（no-op モック）。 */
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 村の存在秘匿ゲート。実物へ委譲させるため {@link VillageAccessGateTestSupport} で結線する。 */
    @Mock
    private VillageAccessGate accessGate;

    @InjectMocks
    private VillageMeetupService service;

    /**
     * 村サービスの村存在確認は {@link VillageAccessGate} へ移った。
     * モックのゲートに実物のゲート（同じモックのリポジトリを注入）を委譲させることで、
     * 本テストが積み上げてきた {@code villageRepository.findById} の stub をそのまま生かしつつ、
     * 可視性判定は実物のロジックで走らせる。
     */
    @BeforeEach
    void wireVillageAccessGate() {
        VillageAccessGateTestSupport.delegateToRealGate(accessGate, villageRepository, membershipRepository);
    }

    // ========================================================================
    // create
    // ========================================================================

    @Test
    @DisplayName("create: 正常系 → PLANNING で保存・候補日も登録")
    void create_ok() {
        givenActiveVillage();
        givenActorIsVillager();
        MeetupCreateRequest req = new MeetupCreateRequest(
                "新年会", "みんなで集まろう", "渋谷駅",
                List.of(new MeetupCandidateDateInput(LocalDate.of(2026, 1, 10), null),
                        new MeetupCandidateDateInput(LocalDate.of(2026, 1, 17), LocalTime.of(19, 0))));
        given(meetupRepository.save(any(VillageMeetupEntity.class)))
                .willAnswer(inv -> {
                    VillageMeetupEntity e = inv.getArgument(0);
                    e.setId(MEETUP_ID);
                    e.setCreatedAt(LocalDateTime.now());
                    return e;
                });
        given(candidateDateRepository.save(any(VillageMeetupCandidateDateEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of()); // detail 取得時の候補日（空でも問題なし）

        MeetupResponse res = service.createMeetup(VILLAGE_ID, req, ACTOR_USER_ID);

        assertThat(res.status()).isEqualTo(VillageMeetupStatus.PLANNING);
        assertThat(res.organizerUserId()).isEqualTo(ACTOR_USER_ID);
        // AC-3: 保存された候補日エンティティに時刻が正しく載る（終日=null と 19:00 の両方）
        org.mockito.ArgumentCaptor<VillageMeetupCandidateDateEntity> cdCaptor =
                org.mockito.ArgumentCaptor.forClass(VillageMeetupCandidateDateEntity.class);
        verify(candidateDateRepository, org.mockito.Mockito.times(2)).save(cdCaptor.capture());
        assertThat(cdCaptor.getAllValues())
                .extracting(VillageMeetupCandidateDateEntity::getCandidateTime)
                .containsExactly(null, LocalTime.of(19, 0));
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_MEETUP_CREATED.name()),
                eq(ACTOR_USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("create: 村人でない → 403 MEETUP_NOT_MEMBER")
    void create_not_member() {
        givenActiveVillage();
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.empty());
        MeetupCreateRequest req = new MeetupCreateRequest(
                "X", null, null, List.of(new MeetupCandidateDateInput(LocalDate.of(2026, 1, 10), null)));

        assertThatThrownBy(() -> service.createMeetup(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MEETUP_NOT_MEMBER);
    }

    @Test
    @DisplayName("create: 候補日に重複あり（同一 date・同一 time）→ 409 VOTE_DUPLICATE")
    void create_duplicate_dates() {
        givenActiveVillage();
        givenActorIsVillager();
        LocalDate d = LocalDate.of(2026, 1, 10);
        // 終日候補（time=null）同士の重複も弾く（MySQL UNIQUE では NULL 重複を許容するためアプリ層で検査）
        MeetupCreateRequest req = new MeetupCreateRequest("X", null, null,
                List.of(new MeetupCandidateDateInput(d, null), new MeetupCandidateDateInput(d, null)));

        assertThatThrownBy(() -> service.createMeetup(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VOTE_DUPLICATE);
        verify(meetupRepository, never()).save(any());
    }

    @Test
    @DisplayName("create: AC-4 同一日でも時刻が異なれば共存できる（重複扱いにしない）")
    void create_same_date_different_time_ok() {
        givenActiveVillage();
        givenActorIsVillager();
        LocalDate d = LocalDate.of(2026, 1, 10);
        MeetupCreateRequest req = new MeetupCreateRequest("X", null, null,
                List.of(new MeetupCandidateDateInput(d, LocalTime.of(10, 0)),
                        new MeetupCandidateDateInput(d, LocalTime.of(19, 0))));
        given(meetupRepository.save(any(VillageMeetupEntity.class)))
                .willAnswer(inv -> {
                    VillageMeetupEntity e = inv.getArgument(0);
                    e.setId(MEETUP_ID);
                    e.setCreatedAt(LocalDateTime.now());
                    return e;
                });
        given(candidateDateRepository.save(any(VillageMeetupCandidateDateEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of());

        MeetupResponse res = service.createMeetup(VILLAGE_ID, req, ACTOR_USER_ID);

        assertThat(res.status()).isEqualTo(VillageMeetupStatus.PLANNING);
        verify(candidateDateRepository, org.mockito.Mockito.times(2)).save(any());
    }

    // ========================================================================
    // update
    // ========================================================================

    @Test
    @DisplayName("update: 正常系（幹事による title 変更）")
    void update_ok() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        given(meetupRepository.save(any(VillageMeetupEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of());

        MeetupUpdateRequest req = new MeetupUpdateRequest("新タイトル", null, null, null);
        MeetupResponse res = service.updateMeetup(VILLAGE_ID, MEETUP_ID, req, ACTOR_USER_ID);

        assertThat(res.title()).isEqualTo("新タイトル");
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_MEETUP_UPDATED.name()),
                eq(ACTOR_USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("update: 幹事でないユーザー → 403 MODERATION_FORBIDDEN")
    void update_not_organizer() {
        givenActiveVillage();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, OTHER_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));

        MeetupUpdateRequest req = new MeetupUpdateRequest("X", null, null, null);
        assertThatThrownBy(() -> service.updateMeetup(VILLAGE_ID, MEETUP_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("update: BAN 済みの幹事本人は更新できない（認可 Wave3・村ロットA・真の穴の修正）")
    void update_banned_organizer() {
        givenActiveVillage();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        // BAN 済み（退村扱い）のため findActiveByVillageIdAndSubject は現役メンバーを返さない
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.empty());

        MeetupUpdateRequest req = new MeetupUpdateRequest("BAN逃れの改ざん", null, null, null);
        assertThatThrownBy(() -> service.updateMeetup(VILLAGE_ID, MEETUP_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);

        verify(meetupRepository, never()).save(any());
    }

    @Test
    @DisplayName("update: CONFIRMED の寄合 → 409 MEETUP_INVALID_STATUS")
    void update_confirmed_rejected() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.CONFIRMED, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));

        MeetupUpdateRequest req = new MeetupUpdateRequest("X", null, null, null);
        assertThatThrownBy(() -> service.updateMeetup(VILLAGE_ID, MEETUP_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MEETUP_INVALID_STATUS);
    }

    // ========================================================================
    // cancel
    // ========================================================================

    @Test
    @DisplayName("cancel: 正常系 → CANCELLED に遷移")
    void cancel_ok() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        given(meetupRepository.save(any(VillageMeetupEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of());

        MeetupResponse res = service.cancelMeetup(VILLAGE_ID, MEETUP_ID, ACTOR_USER_ID);

        assertThat(res.status()).isEqualTo(VillageMeetupStatus.CANCELLED);
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_MEETUP_CANCELLED.name()),
                eq(ACTOR_USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("cancel: CONFIRMED → 409 MEETUP_INVALID_STATUS")
    void cancel_confirmed_rejected() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.CONFIRMED, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.cancelMeetup(VILLAGE_ID, MEETUP_ID, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MEETUP_INVALID_STATUS);
    }

    // ========================================================================
    // confirm
    // ========================================================================

    @Test
    @DisplayName("confirm: AC-5 正常系 → CONFIRMED + 候補の date/time が confirmed へ転記")
    void confirm_ok() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        // 時刻付きの候補（19:00）を確定 → confirmedTime へ転記される
        VillageMeetupCandidateDateEntity cd =
                candidateDate(LocalDate.of(2026, 1, 10), LocalTime.of(19, 0), MEETUP_ID);
        given(candidateDateRepository.findById(CANDIDATE_DATE_ID)).willReturn(Optional.of(cd));
        given(meetupRepository.save(any(VillageMeetupEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of());

        MeetupResponse res = service.confirmMeetup(VILLAGE_ID, MEETUP_ID,
                new MeetupConfirmRequest(CANDIDATE_DATE_ID), ACTOR_USER_ID);

        assertThat(res.status()).isEqualTo(VillageMeetupStatus.CONFIRMED);
        assertThat(res.confirmedDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(res.confirmedTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(existing.getConfirmedTime()).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    @DisplayName("confirm: AC-5b 終日候補（time=null）を確定すると confirmedTime も null のまま")
    void confirm_allday_keeps_null_time() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        VillageMeetupCandidateDateEntity cd = candidateDate(LocalDate.of(2026, 1, 10), null, MEETUP_ID);
        given(candidateDateRepository.findById(CANDIDATE_DATE_ID)).willReturn(Optional.of(cd));
        given(meetupRepository.save(any(VillageMeetupEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of());

        MeetupResponse res = service.confirmMeetup(VILLAGE_ID, MEETUP_ID,
                new MeetupConfirmRequest(CANDIDATE_DATE_ID), ACTOR_USER_ID);

        assertThat(res.confirmedDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(res.confirmedTime()).isNull();
    }

    @Test
    @DisplayName("confirm: 既に CONFIRMED → 409 MEETUP_ALREADY_CONFIRMED")
    void confirm_already_confirmed() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.CONFIRMED, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.confirmMeetup(VILLAGE_ID, MEETUP_ID,
                new MeetupConfirmRequest(CANDIDATE_DATE_ID), ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MEETUP_ALREADY_CONFIRMED);
    }

    @Test
    @DisplayName("confirm: 別寄合の候補日 ID → 404 CANDIDATE_DATE_NOT_FOUND (IDOR防止)")
    void confirm_idor_other_meetup_candidate() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        // 別寄合に属する候補日
        VillageMeetupCandidateDateEntity wrong = candidateDate(LocalDate.of(2026, 1, 10),
                UUID.fromString("01956c00-0000-7000-8000-0000000009ff"));
        given(candidateDateRepository.findById(CANDIDATE_DATE_ID)).willReturn(Optional.of(wrong));

        assertThatThrownBy(() -> service.confirmMeetup(VILLAGE_ID, MEETUP_ID,
                new MeetupConfirmRequest(CANDIDATE_DATE_ID), ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.CANDIDATE_DATE_NOT_FOUND);
    }

    // ========================================================================
    // castVote
    // ========================================================================

    @Test
    @DisplayName("castVote: 新規投票 → save 呼出し + 監査ログ")
    void cast_vote_new() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, OTHER_USER_ID); // 投票者は幹事でなくてもOK
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        VillageMeetupCandidateDateEntity cd = candidateDate(LocalDate.of(2026, 1, 10), MEETUP_ID);
        given(candidateDateRepository.findById(CANDIDATE_DATE_ID)).willReturn(Optional.of(cd));
        given(voteRepository.findByCandidateDateIdAndVoterUserId(CANDIDATE_DATE_ID, ACTOR_USER_ID))
                .willReturn(Optional.empty());
        given(voteRepository.save(any(VillageMeetupVoteEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.castVote(VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID,
                new MeetupVoteRequest(VillageMeetupVoteType.AVAILABLE), ACTOR_USER_ID);

        verify(voteRepository).save(any(VillageMeetupVoteEntity.class));
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_MEETUP_VOTED.name()),
                eq(ACTOR_USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("castVote: 再投票（既存上書き）→ vote_type だけ変わる")
    void cast_vote_update_existing() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, OTHER_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        VillageMeetupCandidateDateEntity cd = candidateDate(LocalDate.of(2026, 1, 10), MEETUP_ID);
        given(candidateDateRepository.findById(CANDIDATE_DATE_ID)).willReturn(Optional.of(cd));
        VillageMeetupVoteEntity prev = VillageMeetupVoteEntity.builder()
                .candidateDateId(CANDIDATE_DATE_ID)
                .voterUserId(ACTOR_USER_ID)
                .voteType(VillageMeetupVoteType.AVAILABLE)
                .votedAt(LocalDateTime.now().minusHours(1))
                .build();
        prev.setId(UUID.randomUUID());
        given(voteRepository.findByCandidateDateIdAndVoterUserId(CANDIDATE_DATE_ID, ACTOR_USER_ID))
                .willReturn(Optional.of(prev));
        given(voteRepository.save(any(VillageMeetupVoteEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.castVote(VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID,
                new MeetupVoteRequest(VillageMeetupVoteType.UNAVAILABLE), ACTOR_USER_ID);

        assertThat(prev.getVoteType()).isEqualTo(VillageMeetupVoteType.UNAVAILABLE);
    }

    // ========================================================================
    // getVoteSummary
    // ========================================================================

    @Test
    @DisplayName("getVoteSummary: 候補日ごとに AVAILABLE/MAYBE/UNAVAILABLE 集計")
    void vote_summary_ok() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));

        VillageMeetupCandidateDateEntity cd1 = candidateDate(LocalDate.of(2026, 1, 10), MEETUP_ID);
        VillageMeetupCandidateDateEntity cd2 = candidateDate(LocalDate.of(2026, 1, 17), MEETUP_ID);
        cd1.setId(CANDIDATE_DATE_ID);
        cd2.setId(OTHER_CANDIDATE_DATE_ID);
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of(cd1, cd2));

        // cd1: AVAILABLE x2, MAYBE x1  / cd2: UNAVAILABLE x1
        VillageMeetupVoteEntity v1 = voteEntity(CANDIDATE_DATE_ID, 1L, VillageMeetupVoteType.AVAILABLE);
        VillageMeetupVoteEntity v2 = voteEntity(CANDIDATE_DATE_ID, 2L, VillageMeetupVoteType.AVAILABLE);
        VillageMeetupVoteEntity v3 = voteEntity(CANDIDATE_DATE_ID, 3L, VillageMeetupVoteType.MAYBE);
        VillageMeetupVoteEntity v4 = voteEntity(OTHER_CANDIDATE_DATE_ID, 4L, VillageMeetupVoteType.UNAVAILABLE);
        given(voteRepository.findByCandidateDateIdIn(List.of(CANDIDATE_DATE_ID, OTHER_CANDIDATE_DATE_ID)))
                .willReturn(List.of(v1, v2, v3, v4));

        MeetupVoteSummaryResponse res = service.getVoteSummary(VILLAGE_ID, MEETUP_ID, ACTOR_USER_ID);

        assertThat(res.candidates()).hasSize(2);
        MeetupVoteSummaryResponse.CandidateDateSummary s1 = res.candidates().get(0);
        assertThat(s1.candidateDateId()).isEqualTo(CANDIDATE_DATE_ID);
        assertThat(s1.availableCount()).isEqualTo(2);
        assertThat(s1.maybeCount()).isEqualTo(1);
        assertThat(s1.unavailableCount()).isEqualTo(0);
        MeetupVoteSummaryResponse.CandidateDateSummary s2 = res.candidates().get(1);
        assertThat(s2.unavailableCount()).isEqualTo(1);
    }

    // ========================================================================
    // 候補日追加
    // ========================================================================

    @Test
    @DisplayName("addCandidateDate: 同一 (date, time) 既存 → 409 VOTE_DUPLICATE（終日同士も弾く）")
    void add_candidate_duplicate() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        // 既存は終日候補（time=null）。追加も終日 → (date, null) ペアが一致するので重複
        VillageMeetupCandidateDateEntity dup = candidateDate(LocalDate.of(2026, 1, 10), null, MEETUP_ID);
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of(dup));

        assertThatThrownBy(() -> service.addCandidateDate(VILLAGE_ID, MEETUP_ID,
                new MeetupCandidateDateAddRequest(LocalDate.of(2026, 1, 10), null, 0), ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VOTE_DUPLICATE);
    }

    @Test
    @DisplayName("addCandidateDate: AC-6 同一日でも時刻が違えば追加できる（重複にしない・時刻も保存）")
    void add_candidate_same_date_different_time_ok() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity existing = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(existing));
        // 既存は 10:00 の候補。追加は同日 19:00 → 別ペアなので許可
        VillageMeetupCandidateDateEntity morning =
                candidateDate(LocalDate.of(2026, 1, 10), LocalTime.of(10, 0), MEETUP_ID);
        given(candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(MEETUP_ID))
                .willReturn(List.of(morning));
        given(candidateDateRepository.save(any(VillageMeetupCandidateDateEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        MeetupCandidateDateResponse res = service.addCandidateDate(VILLAGE_ID, MEETUP_ID,
                new MeetupCandidateDateAddRequest(LocalDate.of(2026, 1, 10), LocalTime.of(19, 0), 1),
                ACTOR_USER_ID);

        assertThat(res.candidateDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(res.candidateTime()).isEqualTo(LocalTime.of(19, 0));
    }

    // ========================================================================
    // get: IDOR
    // ========================================================================

    @Test
    @DisplayName("get: 別の村の meetup ID → 404 MEETUP_NOT_FOUND (IDOR防止)")
    void get_idor_protection() {
        givenActiveVillage();
        givenActorIsVillager();
        VillageMeetupEntity wrong = meetup(VillageMeetupStatus.PLANNING, ACTOR_USER_ID);
        wrong.setVillageId(OTHER_VILLAGE_ID);
        given(meetupRepository.findById(MEETUP_ID)).willReturn(Optional.of(wrong));

        assertThatThrownBy(() -> service.getMeetup(VILLAGE_ID, MEETUP_ID, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MEETUP_NOT_FOUND);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private void givenActiveVillage() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village()));
    }

    private void givenActorIsVillager() {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(ACTOR_USER_ID)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now().minusDays(10))
                .build();
        m.setId(UUID.randomUUID());
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(m));
    }

    private VillageEntity village() {
        VillageEntity v = VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(10L)
                .build();
        v.setId(VILLAGE_ID);
        return v;
    }

    private VillageMeetupEntity meetup(VillageMeetupStatus status, Long organizerId) {
        VillageMeetupEntity e = VillageMeetupEntity.builder()
                .villageId(VILLAGE_ID)
                .title("既存タイトル")
                .description("既存説明")
                .organizerUserId(organizerId)
                .status(status)
                .build();
        e.setId(MEETUP_ID);
        e.setCreatedAt(LocalDateTime.now().minusDays(1));
        return e;
    }

    private VillageMeetupCandidateDateEntity candidateDate(LocalDate date, UUID meetupId) {
        return candidateDate(date, null, meetupId);
    }

    private VillageMeetupCandidateDateEntity candidateDate(LocalDate date, LocalTime time, UUID meetupId) {
        VillageMeetupCandidateDateEntity cd = VillageMeetupCandidateDateEntity.builder()
                .meetupId(meetupId)
                .candidateDate(date)
                .candidateTime(time)
                .sortOrder(0)
                .build();
        cd.setId(CANDIDATE_DATE_ID);
        return cd;
    }

    private VillageMeetupVoteEntity voteEntity(UUID candidateDateId, Long voterId, VillageMeetupVoteType type) {
        VillageMeetupVoteEntity v = VillageMeetupVoteEntity.builder()
                .candidateDateId(candidateDateId)
                .voterUserId(voterId)
                .voteType(type)
                .votedAt(LocalDateTime.now())
                .build();
        v.setId(UUID.randomUUID());
        return v;
    }
}
