package com.mannschaft.app.social.controller;

import com.mannschaft.app.role.service.FriendRequestSourceTeamQueryService;
import com.mannschaft.app.social.service.TeamFriendRequestFacade;
import org.springframework.context.annotation.Import;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.service.TeamFriendFollowBySlugService;
import com.mannschaft.app.social.service.TeamFriendsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261012-0315 フレンドチームのフォロー先を slug で指定する — Controller 層の試練（red）。
 *
 * <h2>実装側が満たすべき契約</h2>
 * <ul>
 *   <li>{@code FollowTeamRequest} に {@code targetTeamSlug}（String・JSON キー {@code "targetTeamSlug"}）を持たせ、
 *       {@code targetTeamId} の {@code @NotNull} を外す。{@code targetTeamId} と {@code targetTeamSlug} は
 *       <b>ちょうど一方だけ</b>必須。両方指定・両方なしは Bean Validation 違反（{@code @AssertTrue} 等）として
 *       {@code MethodArgumentNotValidException} → <b>400 COMMON_001</b> にする。</li>
 *   <li>{@code targetTeamSlug} の空文字・空白のみ・規則外（{@code SlugValidator#isValidFormat}：英小文字・数字・
 *       ハイフン、3〜30 文字、先頭末尾ハイフン不可、連続ハイフン不可、数字のみ不可）も <b>400 COMMON_001</b>。
 *       いずれも Service を一切呼ばない。</li>
 *   <li>Controller は {@code targetTeamSlug} 指定時に
 *       {@code TeamFriendFollowBySlugService#followBySlug(teamId, targetTeamSlug, userId)} を、{@code targetTeamId} 指定時に
 *       従来の {@code follow(teamId, targetTeamId, userId)} を呼ぶ。成功は従来どおり 201（NOWAIT 時 202）。</li>
 * </ul>
 */
@WebMvcTest(TeamFriendsController.class)
@Import(TeamFriendRequestFacade.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CMP-261012-0315 TeamFriendsController slug 指定フォロー（試練）")
class TeamFriendsControllerFollowSlugTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;
    private static final String TARGET_SLUG = "rival-fc";
    private static final String FOLLOW_PATH = "/api/v1/teams/{id}/friends/follow";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FriendRequestSourceTeamQueryService sourceQueryService;

    @MockitoBean
    private TeamFriendsService teamFriendsService;
    @MockitoBean
    private TeamFriendFollowBySlugService followBySlugService;
    @MockitoBean
    private AccessControlService accessControlService;
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        given(sourceQueryService.isEligibleSource(USER_ID, TEAM_ID)).willReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private ResultActions postFollow(String body) throws Exception {
        return mockMvc.perform(post(FOLLOW_PATH, TEAM_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static FollowTeamResponse oneWay() {
        return FollowTeamResponse.builder()
                .followId(100L)
                .followerTeamId(TEAM_ID)
                .followedTeamId(TARGET_TEAM_ID)
                .mutual(false)
                .createdAt(LocalDateTime.of(2026, 10, 12, 3, 15))
                .build();
    }

    @Nested
    @DisplayName("正常系")
    class Success {

        @Test
        @DisplayName("AC-1: targetTeamSlug 指定は followBySlug に委譲して 201 Created")
        void ac1_slug指定は201() throws Exception {
            given(followBySlugService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID)).willReturn(oneWay());

            postFollow("{\"targetTeamSlug\":\"rival-fc\",\"comment\":\"よろしく\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.followId").value(100))
                    .andExpect(jsonPath("$.data.followedTeamId").value(20));

            verify(followBySlugService).followBySlug(TEAM_ID, TARGET_SLUG, USER_ID);
        }

        @Test
        @DisplayName("AC-5: targetTeamId（数値）指定は従来どおり follow に委譲して 201 Created")
        void ac5_数値指定は従来どおり201() throws Exception {
            given(teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID)).willReturn(oneWay());

            postFollow("{\"targetTeamId\":20}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.followId").value(100));

            verify(teamFriendsService).follow(TEAM_ID, TARGET_TEAM_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("Service 由来のエラー写像（slug 経路）")
    class ServiceErrors {

        @Test
        @DisplayName("AC-2: 不在の slug は 404 SOCIAL_104")
        void ac2_不在slugは404() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND))
                    .given(followBySlugService).followBySlug(eq(TEAM_ID), eq("no-such-team"), eq(USER_ID));

            postFollow("{\"targetTeamSlug\":\"no-such-team\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_104"));
        }

        @Test
        @DisplayName("AC-3: 自チームの slug は 400 SOCIAL_101")
        void ac3_自チームslugは400() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW))
                    .given(followBySlugService).followBySlug(eq(TEAM_ID), eq("my-team"), eq(USER_ID));

            postFollow("{\"targetTeamSlug\":\"my-team\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_101"));
        }

        @Test
        @DisplayName("AC-6: 権限不足の slug 指定は 403 COMMON_002")
        void ac6_権限不足は403() throws Exception {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(followBySlugService).followBySlug(eq(TEAM_ID), eq(TARGET_SLUG), eq(USER_ID));

            postFollow("{\"targetTeamSlug\":\"rival-fc\"}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-7: アーカイブ済み等のフォロー先（slug）は 404 SOCIAL_104")
        void ac7_利用不可slugは404() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND))
                    .given(followBySlugService).followBySlug(eq(TEAM_ID), eq("archived-fc"), eq(USER_ID));

            postFollow("{\"targetTeamSlug\":\"archived-fc\"}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_104"));
        }
    }

    @Nested
    @DisplayName("AC-4: targetTeamId と targetTeamSlug はちょうど一方だけ")
    class ExactlyOne {

        @Test
        @DisplayName("AC-4: 両方指定は 400 COMMON_001 で Service を呼ばない")
        void ac4_両方指定は400() throws Exception {
            postFollow("{\"targetTeamId\":20,\"targetTeamSlug\":\"rival-fc\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));

            verifyNoInteractions(teamFriendsService, followBySlugService);
        }

        @Test
        @DisplayName("AC-4: 両方なしは 400 COMMON_001 で Service を呼ばない")
        void ac4_両方なしは400() throws Exception {
            postFollow("{\"comment\":\"よろしく\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));

            verifyNoInteractions(teamFriendsService, followBySlugService);
        }
    }

    @Nested
    @DisplayName("AC-18: slug の書式検証")
    class SlugFormat {

        @ParameterizedTest(name = "AC-18: targetTeamSlug=\"{0}\" は 400 COMMON_001")
        @ValueSource(strings = {
                "",                                  // 空文字
                "   ",                               // 空白のみ
                "ab",                                // 短すぎる（2文字）
                "abcdefghij-abcdefghij-abcdefghij",  // 長すぎる（32文字）
                "Rival-FC",                          // 大文字
                "rival_fc",                          // 規則外の記号
                "-rival",                            // 先頭ハイフン
                "rival-",                            // 末尾ハイフン
                "rival--fc",                         // 連続ハイフン
                "12345",                             // 数字のみ（内部ID高速パスと衝突）
                "らいばる"                            // 非 ASCII
        })
        @DisplayName("AC-18: 空文字・空白のみ・規則外の slug は 400 COMMON_001 で Service を呼ばない")
        void ac18_不正slugは400(String slug) throws Exception {
            postFollow("{\"targetTeamSlug\":\"" + slug + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));

            verifyNoInteractions(teamFriendsService, followBySlugService);
        }

        @Test
        @DisplayName("AC-18: 境界（3文字・30文字）の正しい slug は受け付ける")
        void ac18_境界の正しいslugは受け付ける() throws Exception {
            String thirty = "abcdefghij-abcdefghij-abcdefgh"; // 30 文字
            given(followBySlugService.followBySlug(TEAM_ID, "abc", USER_ID)).willReturn(oneWay());
            given(followBySlugService.followBySlug(TEAM_ID, thirty, USER_ID)).willReturn(oneWay());

            postFollow("{\"targetTeamSlug\":\"abc\"}").andExpect(status().isCreated());
            postFollow("{\"targetTeamSlug\":\"" + thirty + "\"}").andExpect(status().isCreated());
        }
    }
    @ParameterizedTest(name = "FR-13: slug指定={0}, comment長={1}")
    @CsvSource({
            "true, -2", "true, -1", "true, 0", "true, 300", "true, 301",
            "false, -2", "false, -1", "false, 0", "false, 300", "false, 301"
    })
    @DisplayName("FR-13: コメントは省略・null・0〜300文字を許可し301文字は委譲前に拒否する")
    void コメントの既存HTTP境界を保つ(boolean slug指定, int length) throws Exception {
        String target = slug指定 ? "\"targetTeamSlug\":\"rival-fc\"" : "\"targetTeamId\":20";
        String comment = length == -2 ? "" : length == -1 ? ",\"comment\":null"
                : ",\"comment\":\"" + "あ".repeat(length) + "\"";

        if (length == 301) {
            postFollow("{" + target + comment + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
            verifyNoInteractions(sourceQueryService, teamFriendsService, followBySlugService);
        } else if (slug指定) {
            given(followBySlugService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID)).willReturn(oneWay());
            postFollow("{" + target + comment + "}").andExpect(status().isCreated());
            verify(followBySlugService).followBySlug(TEAM_ID, TARGET_SLUG, USER_ID);
        } else {
            given(teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID)).willReturn(oneWay());
            postFollow("{" + target + comment + "}").andExpect(status().isCreated());
            verify(teamFriendsService).follow(TEAM_ID, TARGET_TEAM_ID, USER_ID);
        }
    }

}
