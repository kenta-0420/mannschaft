package com.mannschaft.app.social.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.social.controller.FollowController;
import com.mannschaft.app.social.controller.SocialProfileController;
import com.mannschaft.app.social.dto.CreateProfileRequest;
import com.mannschaft.app.social.dto.FollowRequest;
import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.dto.ProfileResponse;
import com.mannschaft.app.social.dto.UpdateProfileRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FollowController} および {@link SocialProfileController} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Social コントローラー単体テスト")
class SocialControllerTest {

    private static final Long USER_ID = 1L;

    // FollowController
    @Mock private FollowService followService;
    @InjectMocks private FollowController followController;

    // SocialProfileController
    @Mock private SocialProfileService profileService;
    @InjectMocks private SocialProfileController profileController;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- FollowController ----

    @Test
    @DisplayName("follow: 201 Created")
    void follow_201() {
        FollowRequest req = new FollowRequest("USER", 99L);
        FollowResponse followResp = new FollowResponse(1L, "USER", USER_ID, "USER", 99L, LocalDateTime.now());
        given(followService.follow("USER", 99L, USER_ID)).willReturn(followResp);

        ResponseEntity<ApiResponse<FollowResponse>> resp = followController.follow(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().getData().getFollowedId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("unfollow: 204 No Content")
    void unfollow_204() {
        ResponseEntity<Void> resp = followController.unfollow("USER", 99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(followService).unfollow("USER", 99L, USER_ID);
    }

    @Test
    @DisplayName("getFollowing: 200 OK")
    void getFollowing_200() {
        given(followService.getFollowing(USER_ID, 20)).willReturn(List.of());

        ResponseEntity<ApiResponse<List<FollowResponse>>> resp = followController.getFollowing(20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("getFollowers: 200 OK")
    void getFollowers_200() {
        given(followService.getFollowers(USER_ID, 20)).willReturn(List.of());

        ResponseEntity<ApiResponse<List<FollowResponse>>> resp = followController.getFollowers(20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("isFollowing: 200 OK (フォロー中)")
    void isFollowing_200_true() {
        given(followService.isFollowing("USER", 99L, USER_ID)).willReturn(true);

        ResponseEntity<ApiResponse<Boolean>> resp = followController.isFollowing("USER", 99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData()).isTrue();
    }

    // ---- SocialProfileController ----

    private ProfileResponse sampleProfile() {
        return new ProfileResponse(1L, USER_ID, "testhandle", "テストユーザー",
                null, "自己紹介", true, 5L, 10L, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("createProfile: 201 Created")
    void createProfile_201() {
        CreateProfileRequest req = new CreateProfileRequest("testhandle", "テストユーザー", null, null);
        given(profileService.createProfile(req, USER_ID)).willReturn(sampleProfile());

        ResponseEntity<ApiResponse<ProfileResponse>> resp = profileController.createProfile(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("getMyProfile: 200 OK")
    void getMyProfile_200() {
        given(profileService.getMyProfile(USER_ID)).willReturn(sampleProfile());

        ResponseEntity<ApiResponse<ProfileResponse>> resp = profileController.getMyProfile();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().getHandle()).isEqualTo("testhandle");
    }

    @Test
    @DisplayName("updateProfile: 200 OK")
    void updateProfile_200() {
        UpdateProfileRequest req = new UpdateProfileRequest(null, "新しい名前", null, null);
        given(profileService.updateProfile(req, USER_ID)).willReturn(sampleProfile());

        ResponseEntity<ApiResponse<ProfileResponse>> resp = profileController.updateProfile(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("getProfileByHandle: 200 OK")
    void getProfileByHandle_200() {
        given(profileService.getProfileByHandle("testhandle")).willReturn(sampleProfile());

        ResponseEntity<ApiResponse<ProfileResponse>> resp = profileController.getProfileByHandle("testhandle");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("getProfileByUserId: 200 OK")
    void getProfileByUserId_200() {
        given(profileService.getProfileByUserId(USER_ID)).willReturn(sampleProfile());

        ResponseEntity<ApiResponse<ProfileResponse>> resp = profileController.getProfileByUserId(USER_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("deactivateProfile: 204 No Content")
    void deactivateProfile_204() {
        ResponseEntity<Void> resp = profileController.deactivateProfile();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(profileService).deactivateProfile(USER_ID);
    }
}
