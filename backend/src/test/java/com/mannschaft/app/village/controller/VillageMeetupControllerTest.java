package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.dto.MeetupCandidateDateInput;
import com.mannschaft.app.village.dto.MeetupCandidateDateResponse;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.MeetupResponse;
import com.mannschaft.app.village.dto.MeetupVoteRequest;
import com.mannschaft.app.village.dto.MeetupVoteSummaryResponse;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupVoteType;
import com.mannschaft.app.village.service.VillageMeetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.1 Phase 3-β — VillageMeetupController MockMvc 契約テスト。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p>本テストは <strong>characterization test（現契約の固定）</strong> であり、TDD の red → green
 * サイクルにおける red テストではない。<strong>BE は既に正しく、初回実行から green になるのが正常</strong>である。
 * 「試練が red にならない」ことは異常ではない。</p>
 *
 * <p>2026-07-15 の実機精査で、村ドメインに FE/BE の契約不一致が 17 件確定した。真因は、村ドメインの
 * Controller テストに 2 流派が混在していたことにある:</p>
 *
 * <ul>
 *   <li>{@code *ControllerTest} — MockMvc 経由。HTTP 層を通るため URL パス・HTTP メソッド・
 *       {@code @RequestParam} の enum バインド・JSON エンベロープ形状を検証できる。</li>
 *   <li>{@code *ControllerIntegrationTest} — {@code @Autowired} した Controller Bean のメソッドを
 *       直接呼ぶ。HTTP 層を完全に迂回するため、上記を <strong>一切検証しない</strong>。</li>
 * </ul>
 *
 * <p>寄合（Meetup）は Controller テストが皆無（{@code VillageMeetupServiceTest} のみ）であり、
 * 判明分だけで 6 件破損していた。本テストはその再発を機械的に防ぐ回帰防止柵である。</p>
 *
 * <p>規約: 新規 Controller テストは MockMvc 経由必須（{@code TEST_CONVENTION.md} §Controller テスト）。</p>
 */
@WebMvcTest(VillageMeetupController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 VillageMeetupController MockMvc 契約テスト（現契約の固定）")
class VillageMeetupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageMeetupService meetupService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final UUID MEETUP_ID = UUID.randomUUID();
    private static final UUID CANDIDATE_DATE_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;

    private static final String BASE = "/api/v1/villages/{villageId}/meetups";

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    private MeetupCandidateDateResponse candidateDate() {
        return MeetupCandidateDateResponse.builder()
                .id(CANDIDATE_DATE_ID)
                .meetupId(MEETUP_ID)
                .candidateDate(LocalDate.of(2026, 8, 1))
                .candidateTime(LocalTime.of(18, 30))
                .sortOrder(0)
                .build();
    }

    private MeetupResponse meetup() {
        return MeetupResponse.builder()
                .id(MEETUP_ID)
                .villageId(VILLAGE_ID)
                .title("夏の寄合")
                .description("納涼会")
                .organizerUserId(USER_ID)
                .status(VillageMeetupStatus.PLANNING)
                .confirmedDate(null)
                .location("公民館")
                // TZ 境界での 9 時間ズレを避けるため、日時は文字列リテラルではなく LocalDateTime で組む
                .createdAt(LocalDateTime.of(2026, 7, 15, 10, 0))
                .candidateDates(List.of(candidateDate()))
                .build();
    }

    // ==================================================================
    // 投票エンドポイントのパス・HTTP メソッド契約
    // ==================================================================

    @Nested
    @DisplayName("投票エンドポイントのパス・メソッド契約")
    class VotePathContract {

        @Test
        @DisplayName("PUT /{mid}/candidate-dates/{cid}/vote が正準の投票経路であり 204 を返す")
        void castVote_isPutOnCandidateDatePath_returns204() throws Exception {
            String body = """
                    { "voteType": "AVAILABLE" }
                    """;

            mockMvc.perform(put(BASE + "/{meetupId}/candidate-dates/{candidateDateId}/vote",
                            VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());

            ArgumentCaptor<MeetupVoteRequest> captor = ArgumentCaptor.forClass(MeetupVoteRequest.class);
            verify(meetupService).castVote(eq(VILLAGE_ID), eq(MEETUP_ID), eq(CANDIDATE_DATE_ID),
                    captor.capture(), eq(USER_ID));
            assertThat(captor.getValue().voteType()).isEqualTo(VillageMeetupVoteType.AVAILABLE);
        }

        @Test
        @DisplayName("POST /{mid}/votes は存在しない（405）— FE が誤って叩いていた経路を固定する")
        void postVotes_doesNotExist_returns405() throws Exception {
            mockMvc.perform(post(BASE + "/{meetupId}/votes", VILLAGE_ID, MEETUP_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "voteType": "AVAILABLE" }
                                    """))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error.code").value("COMMON_004"));

            verify(meetupService, never()).castVote(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("GET /{mid}/votes/summary は存在しない（404）— 集計は /votes に一本化されている")
        void getVotesSummary_doesNotExist_returns404() throws Exception {
            mockMvc.perform(get(BASE + "/{meetupId}/votes/summary", VILLAGE_ID, MEETUP_ID))
                    .andExpect(status().isNotFound());

            verify(meetupService, never()).getVoteSummary(any(), any(), any());
        }
    }

    // ==================================================================
    // 投票集計レスポンスの形状
    // ==================================================================

    @Test
    @DisplayName("GET /{mid}/votes — 集計は $.data.candidates[] に availableCount/maybeCount/unavailableCount を返す")
    void getVoteSummary_shape() throws Exception {
        MeetupVoteSummaryResponse summary = MeetupVoteSummaryResponse.builder()
                .meetupId(MEETUP_ID)
                .candidates(List.of(MeetupVoteSummaryResponse.CandidateDateSummary.builder()
                        .candidateDateId(CANDIDATE_DATE_ID)
                        .candidateDate(LocalDate.of(2026, 8, 1))
                        .availableCount(3)
                        .maybeCount(2)
                        .unavailableCount(1)
                        .build()))
                .build();
        given(meetupService.getVoteSummary(VILLAGE_ID, MEETUP_ID, USER_ID)).willReturn(summary);

        mockMvc.perform(get(BASE + "/{meetupId}/votes", VILLAGE_ID, MEETUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meetupId").value(MEETUP_ID.toString()))
                .andExpect(jsonPath("$.data.candidates[0].candidateDateId").value(CANDIDATE_DATE_ID.toString()))
                .andExpect(jsonPath("$.data.candidates[0].candidateDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data.candidates[0].availableCount").value(3))
                .andExpect(jsonPath("$.data.candidates[0].maybeCount").value(2))
                .andExpect(jsonPath("$.data.candidates[0].unavailableCount").value(1));
    }

    // ==================================================================
    // voteType の enum バインド
    // ==================================================================

    @Nested
    @DisplayName("voteType の enum 契約")
    class VoteTypeEnumContract {

        @Test
        @DisplayName("voteType は AVAILABLE / MAYBE / UNAVAILABLE を受理する")
        void voteType_acceptsAllThreeValues() throws Exception {
            for (VillageMeetupVoteType type : VillageMeetupVoteType.values()) {
                mockMvc.perform(put(BASE + "/{meetupId}/candidate-dates/{candidateDateId}/vote",
                                VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"voteType\":\"" + type.name() + "\"}"))
                        .andExpect(status().isNoContent());
            }
        }

        @Test
        @DisplayName("voteType=YES は 400（FE が送っていた誤値。AVAILABLE が正）")
        void voteType_yes_isRejected() throws Exception {
            mockMvc.perform(put(BASE + "/{meetupId}/candidate-dates/{candidateDateId}/vote",
                            VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "voteType": "YES" }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(meetupService, never()).castVote(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("voteType=NO は 400（FE が送っていた誤値。UNAVAILABLE が正）")
        void voteType_no_isRejected() throws Exception {
            mockMvc.perform(put(BASE + "/{meetupId}/candidate-dates/{candidateDateId}/vote",
                            VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "voteType": "NO" }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(meetupService, never()).castVote(any(), any(), any(), any(), any());
        }
    }

    // ==================================================================
    // 一覧の status enum バインド
    // ==================================================================

    @Nested
    @DisplayName("一覧 status の enum 契約")
    class ListStatusEnumContract {

        @Test
        @DisplayName("GET ?status=PLANNING は 200（PLANNING が投票受付中の正準値）")
        void list_planning_returns200() throws Exception {
            given(meetupService.listMeetups(eq(VILLAGE_ID), eq(VillageMeetupStatus.PLANNING),
                    eq(USER_ID), any(Pageable.class))).willReturn(List.of(meetup()));

            mockMvc.perform(get(BASE, VILLAGE_ID).param("status", "PLANNING"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].status").value("PLANNING"));
        }

        @Test
        @DisplayName("GET ?status=OPEN は 400 — FE の既定値であり『寄合タブを開いた瞬間 400』の原因")
        void list_open_returns400() throws Exception {
            mockMvc.perform(get(BASE, VILLAGE_ID).param("status", "OPEN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));

            verify(meetupService, never()).listMeetups(any(), any(), any(), any());
        }

        @Test
        @DisplayName("GET ?status=CONFIRMED / CANCELLED も受理される")
        void list_otherValidStatuses_return200() throws Exception {
            given(meetupService.listMeetups(eq(VILLAGE_ID), any(), eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of());

            for (VillageMeetupStatus status : VillageMeetupStatus.values()) {
                mockMvc.perform(get(BASE, VILLAGE_ID).param("status", status.name()))
                        .andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("GET — status 未指定なら null が Service に渡る（全件）")
        void list_withoutStatus_passesNull() throws Exception {
            given(meetupService.listMeetups(eq(VILLAGE_ID), eq(null), eq(USER_ID), any(Pageable.class)))
                    .willReturn(List.of(meetup()));

            mockMvc.perform(get(BASE, VILLAGE_ID))
                    .andExpect(status().isOk());

            verify(meetupService).listMeetups(eq(VILLAGE_ID), eq(null), eq(USER_ID), any(Pageable.class));
        }
    }

    // ==================================================================
    // 一覧エンベロープ形状
    // ==================================================================

    @Test
    @DisplayName("GET 一覧 — $.data は素の配列（Page の content ラップではない）")
    void list_envelopeIsBareArray() throws Exception {
        given(meetupService.listMeetups(eq(VILLAGE_ID), any(), eq(USER_ID), any(Pageable.class)))
                .willReturn(List.of(meetup()));

        mockMvc.perform(get(BASE, VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(MEETUP_ID.toString()))
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").doesNotExist());
    }

    // ==================================================================
    // 作成リクエストの candidateDates 形状
    // ==================================================================

    @Nested
    @DisplayName("寄合作成の candidateDates 契約（#2357 object 配列 {date, time?}）")
    class CreateCandidateDatesContract {

        @Test
        @DisplayName("POST — candidateDates は object 配列 {date, time?} で 201。time 任意（終日は time 省略）")
        void create_candidateDatesAreObjectArray() throws Exception {
            given(meetupService.createMeetup(eq(VILLAGE_ID), any(MeetupCreateRequest.class), eq(USER_ID)))
                    .willReturn(meetup());

            String body = """
                    {
                      "title": "夏の寄合",
                      "description": "納涼会",
                      "location": "公民館",
                      "candidateDates": [
                        { "date": "2026-08-01", "time": "18:30" },
                        { "date": "2026-08-02" }
                      ]
                    }
                    """;

            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(MEETUP_ID.toString()));

            ArgumentCaptor<MeetupCreateRequest> captor = ArgumentCaptor.forClass(MeetupCreateRequest.class);
            verify(meetupService).createMeetup(eq(VILLAGE_ID), captor.capture(), eq(USER_ID));
            assertThat(captor.getValue().candidateDates())
                    .containsExactly(
                            new MeetupCandidateDateInput(LocalDate.of(2026, 8, 1), LocalTime.of(18, 30)),
                            new MeetupCandidateDateInput(LocalDate.of(2026, 8, 2), null));
        }

        @Test
        @DisplayName("POST — 素の日付配列（List<String>）で送ると 400。旧形状はもう受理しない")
        void create_candidateDatesAsPlainStringArray_returns400() throws Exception {
            String body = """
                    {
                      "title": "夏の寄合",
                      "candidateDates": ["2026-08-01", "2026-08-02"]
                    }
                    """;

            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(meetupService, never()).createMeetup(any(), any(), any());
        }

        @Test
        @DisplayName("POST — object 要素に date が無い（{time:...} のみ）は 400（@NotNull date）")
        void create_candidateDateWithoutDate_returns400() throws Exception {
            String body = """
                    {
                      "title": "夏の寄合",
                      "candidateDates": [{ "time": "18:30" }]
                    }
                    """;

            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(meetupService, never()).createMeetup(any(), any(), any());
        }

        @Test
        @DisplayName("POST — candidateDates 空配列は 400（@NotEmpty）")
        void create_emptyCandidateDates_returns400() throws Exception {
            String body = """
                    {
                      "title": "夏の寄合",
                      "candidateDates": []
                    }
                    """;

            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(meetupService, never()).createMeetup(any(), any(), any());
        }
    }

    // ==================================================================
    // MeetupResponse の形状
    // ==================================================================

    @Nested
    @DisplayName("MeetupResponse の形状契約")
    class ResponseShapeContract {

        @Test
        @DisplayName("詳細 — location / confirmedDate を返し、venue / confirmedDateId は存在しない")
        void get_fieldNames() throws Exception {
            MeetupResponse confirmed = MeetupResponse.builder()
                    .id(MEETUP_ID)
                    .villageId(VILLAGE_ID)
                    .title("夏の寄合")
                    .description("納涼会")
                    .organizerUserId(USER_ID)
                    .status(VillageMeetupStatus.CONFIRMED)
                    .confirmedDate(LocalDate.of(2026, 8, 1))
                    .confirmedTime(LocalTime.of(18, 30))
                    .location("公民館")
                    .createdAt(LocalDateTime.of(2026, 7, 15, 10, 0))
                    .candidateDates(List.of(candidateDate()))
                    .build();
            given(meetupService.getMeetup(VILLAGE_ID, MEETUP_ID, USER_ID)).willReturn(confirmed);

            mockMvc.perform(get(BASE + "/{meetupId}", VILLAGE_ID, MEETUP_ID))
                    .andExpect(status().isOk())
                    // 正: location（FE は venue を読んでいた）
                    .andExpect(jsonPath("$.data.location").value("公民館"))
                    .andExpect(jsonPath("$.data.venue").doesNotExist())
                    // 正: confirmedDate は日付そのもの（FE は confirmedDateId を期待していた）
                    .andExpect(jsonPath("$.data.confirmedDate").value("2026-08-01"))
                    // #2357: 確定時刻も返す（終日なら null）
                    .andExpect(jsonPath("$.data.confirmedTime").value("18:30:00"))
                    .andExpect(jsonPath("$.data.confirmedDateId").doesNotExist())
                    // 存在しないフィールド
                    .andExpect(jsonPath("$.data.participantCount").doesNotExist())
                    .andExpect(jsonPath("$.data.updatedAt").doesNotExist())
                    // 存在するフィールド
                    .andExpect(jsonPath("$.data.organizerUserId").value(USER_ID))
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        }

        @Test
        @DisplayName("候補日 DTO は {id, meetupId, candidateDate, sortOrder} のみ")
        void candidateDate_shape() throws Exception {
            given(meetupService.getMeetup(VILLAGE_ID, MEETUP_ID, USER_ID)).willReturn(meetup());

            mockMvc.perform(get(BASE + "/{meetupId}", VILLAGE_ID, MEETUP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidateDates[0].id").value(CANDIDATE_DATE_ID.toString()))
                    .andExpect(jsonPath("$.data.candidateDates[0].meetupId").value(MEETUP_ID.toString()))
                    .andExpect(jsonPath("$.data.candidateDates[0].candidateDate").value("2026-08-01"))
                    // #2357: 候補日 DTO に candidateTime（終日なら null）が載る
                    .andExpect(jsonPath("$.data.candidateDates[0].candidateTime").value("18:30:00"))
                    .andExpect(jsonPath("$.data.candidateDates[0].sortOrder").value(0))
                    // 候補日 DTO に投票集計は含まれない（集計は GET /votes 側の責務）
                    .andExpect(jsonPath("$.data.candidateDates[0].availableCount").doesNotExist())
                    .andExpect(jsonPath("$.data.candidateDates[0].voteCount").doesNotExist())
                    .andExpect(jsonPath("$.data.candidateDates[0].votes").doesNotExist())
                    .andExpect(jsonPath("$.data.candidateDates[0].date").doesNotExist());
        }
    }
}
