package com.mannschaft.app.profile;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.profile.dto.ProfileMediaCommitRequest;
import com.mannschaft.app.profile.dto.ProfileMediaResponse;
import com.mannschaft.app.profile.dto.ProfileMediaUploadUrlRequest;
import com.mannschaft.app.profile.dto.ProfileMediaUploadUrlResponse;
import com.mannschaft.app.profile.service.ProfileMediaService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * {@link ProfileMediaService} の単体テスト。
 * Mockito で R2StorageService / UserRepository / TeamRepository / OrganizationRepository をモックして
 * ビジネスロジック（アップロード URL 発行・コミット・削除）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileMediaService 単体テスト")
class ProfileMediaServiceTest {

    @Mock
    R2StorageService r2StorageService;

    @Mock
    UserRepository userRepository;

    @Mock
    TeamRepository teamRepository;

    @Mock
    OrganizationRepository organizationRepository;

    @InjectMocks
    ProfileMediaService profileMediaService;

    // ==================== テスト用定数 ====================

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    // ==================== ヘルパーメソッド ====================

    /**
     * ProfileMediaUploadUrlRequest をリフレクションで組み立てる。
     */
    private ProfileMediaUploadUrlRequest buildUploadUrlRequest(String contentType, long fileSize) {
        ProfileMediaUploadUrlRequest req = new ProfileMediaUploadUrlRequest();
        ReflectionTestUtils.setField(req, "contentType", contentType);
        ReflectionTestUtils.setField(req, "fileSize", fileSize);
        return req;
    }

    /**
     * ProfileMediaCommitRequest をリフレクションで組み立てる。
     */
    private ProfileMediaCommitRequest buildCommitRequest(String r2Key) {
        ProfileMediaCommitRequest req = new ProfileMediaCommitRequest();
        ReflectionTestUtils.setField(req, "r2Key", r2Key);
        return req;
    }

    /**
     * テスト用 PresignedUploadResult を返す。
     */
    private PresignedUploadResult presignedResult() {
        return new PresignedUploadResult("https://r2.example.com/presigned-put-url", "key", 600L);
    }

    // ==================== generateUploadUrl ====================

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        @DisplayName("正常系_USER_ICON — USER+ICON で Presigned URL が発行される")
        void 正常系_USER_ICON_PresignedURL発行成功() {
            // given
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/jpeg", 1024L * 1024);
            given(userRepository.existsById(USER_ID)).willReturn(true);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/jpeg"), any()))
                    .willReturn(presignedResult());

            // when
            ProfileMediaUploadUrlResponse result = profileMediaService.generateUploadUrl(
                    ProfileMediaScope.USER, USER_ID, ProfileMediaRole.ICON, req);

            // then
            assertThat(result.getUploadUrl()).isEqualTo("https://r2.example.com/presigned-put-url");
            assertThat(result.getR2Key()).startsWith("user/" + USER_ID + "/icon/");
            assertThat(result.getExpiresIn()).isEqualTo(600);
            then(r2StorageService).should().generateUploadUrl(anyString(), eq("image/jpeg"), any());
        }

        @Test
        @DisplayName("正常系_TEAM_BANNER — TEAM+BANNER で Presigned URL が発行される")
        void 正常系_TEAM_BANNER_PresignedURL発行成功() {
            // given
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/png", 5L * 1024 * 1024);
            given(teamRepository.existsById(TEAM_ID)).willReturn(true);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/png"), any()))
                    .willReturn(presignedResult());

            // when
            ProfileMediaUploadUrlResponse result = profileMediaService.generateUploadUrl(
                    ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.BANNER, req);

            // then
            assertThat(result.getUploadUrl()).isEqualTo("https://r2.example.com/presigned-put-url");
            assertThat(result.getR2Key()).startsWith("team/" + TEAM_ID + "/banner/");
            then(r2StorageService).should().generateUploadUrl(anyString(), eq("image/png"), any());
        }

        @Test
        @DisplayName("異常系_GIF_BANNER_BAD_REQUEST — BANNER に image/gif → 400")
        void 異常系_GIF_BANNER_400() {
            // given
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/gif", 1024L * 1024);
            given(teamRepository.existsById(TEAM_ID)).willReturn(true);

            // when / then
            assertThatThrownBy(() -> profileMediaService.generateUploadUrl(
                    ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.BANNER, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("異常系_MIME不正_BAD_REQUEST — 許可外MIME → 400")
        void 異常系_MIME不正_400() {
            // given
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/bmp", 1024L);
            given(userRepository.existsById(USER_ID)).willReturn(true);

            // when / then
            assertThatThrownBy(() -> profileMediaService.generateUploadUrl(
                    ProfileMediaScope.USER, USER_ID, ProfileMediaRole.ICON, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("異常系_ICON_サイズ超過 — ICON に 6MB → 400")
        void 異常系_ICON_サイズ超過_400() {
            // given: 5MB 上限に対して 6MB（超過）
            long oversized = 6L * 1024 * 1024;
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/jpeg", oversized);
            given(userRepository.existsById(USER_ID)).willReturn(true);

            // when / then
            assertThatThrownBy(() -> profileMediaService.generateUploadUrl(
                    ProfileMediaScope.USER, USER_ID, ProfileMediaRole.ICON, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("異常系_BANNER_サイズ超過 — BANNER に 11MB → 400")
        void 異常系_BANNER_サイズ超過_400() {
            // given: 10MB 上限に対して 11MB（超過）
            long oversized = 11L * 1024 * 1024;
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/webp", oversized);
            given(teamRepository.existsById(TEAM_ID)).willReturn(true);

            // when / then
            assertThatThrownBy(() -> profileMediaService.generateUploadUrl(
                    ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.BANNER, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("異常系_USER_存在しない — USER scope でユーザーが見つからない → 404")
        void 異常系_USER_存在しない_404() {
            // given
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/jpeg", 1024L);
            given(userRepository.existsById(USER_ID)).willReturn(false);

            // when / then
            assertThatThrownBy(() -> profileMediaService.generateUploadUrl(
                    ProfileMediaScope.USER, USER_ID, ProfileMediaRole.ICON, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("異常系_TEAM_存在しない — TEAM scope でチームが見つからない → 404")
        void 異常系_TEAM_存在しない_404() {
            // given
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/png", 1024L);
            given(teamRepository.existsById(TEAM_ID)).willReturn(false);

            // when / then
            assertThatThrownBy(() -> profileMediaService.generateUploadUrl(
                    ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.BANNER, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("異常系_ORG_存在しない — ORGANIZATION scope で組織が見つからない → 404")
        void 異常系_ORG_存在しない_404() {
            // given
            ProfileMediaUploadUrlRequest req = buildUploadUrlRequest("image/jpeg", 1024L);
            given(organizationRepository.existsById(ORG_ID)).willReturn(false);

            // when / then
            assertThatThrownBy(() -> profileMediaService.generateUploadUrl(
                    ProfileMediaScope.ORGANIZATION, ORG_ID, ProfileMediaRole.ICON, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ==================== commit ====================

    @Nested
    @DisplayName("commit")
    class Commit {

        @Test
        @DisplayName("正常系_USER_ICON — avatarUrl が更新される")
        void 正常系_USER_ICON_avatarUrl更新() {
            // given
            String r2Key = "user/" + USER_ID + "/icon/some-uuid.jpg";
            ProfileMediaCommitRequest req = buildCommitRequest(r2Key);
            UserEntity mockUser = UserEntity.builder()
                    .email("test@example.com")
                    .lastName("テスト")
                    .firstName("ユーザー")
                    .displayName("テストユーザー")
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(mockUser));
            given(r2StorageService.generateDownloadUrl(eq(r2Key), any()))
                    .willReturn("https://cdn.example.com/user/1/icon/some-uuid.jpg");

            // when
            ProfileMediaResponse result = profileMediaService.commit(
                    ProfileMediaScope.USER, USER_ID, ProfileMediaRole.ICON, req);

            // then
            assertThat(result.getMediaRole()).isEqualTo("icon");
            assertThat(result.getUrl()).isEqualTo("https://cdn.example.com/user/1/icon/some-uuid.jpg");
            // 旧キーが null なので R2 削除は呼ばれない
            then(r2StorageService).should(never()).delete(anyString());
        }

        @Test
        @DisplayName("正常系_TEAM_ICON_旧キー削除 — 旧 iconUrl が R2 から削除される")
        void 正常系_TEAM_ICON_旧キー削除() {
            // given
            String newKey = "team/" + TEAM_ID + "/icon/new-uuid.jpg";
            String oldKey = "team/" + TEAM_ID + "/icon/old-uuid.jpg";
            ProfileMediaCommitRequest req = buildCommitRequest(newKey);
            TeamEntity mockTeam = TeamEntity.builder()
                    .name("テストチーム")
                    .visibility(com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .iconUrl(oldKey)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(mockTeam));
            given(r2StorageService.generateDownloadUrl(eq(newKey), any()))
                    .willReturn("https://cdn.example.com/" + newKey);

            // when
            profileMediaService.commit(ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.ICON, req);

            // then: 旧キーが R2 から削除される
            then(r2StorageService).should().delete(oldKey);
        }

        @Test
        @DisplayName("正常系_TEAM_BANNER — bannerUrl が更新される")
        void 正常系_TEAM_BANNER_bannerUrl更新() {
            // given
            String r2Key = "team/" + TEAM_ID + "/banner/new-uuid.png";
            ProfileMediaCommitRequest req = buildCommitRequest(r2Key);
            TeamEntity mockTeam = TeamEntity.builder()
                    .name("テストチーム")
                    .visibility(com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(mockTeam));
            given(r2StorageService.generateDownloadUrl(eq(r2Key), any()))
                    .willReturn("https://cdn.example.com/" + r2Key);

            // when
            ProfileMediaResponse result = profileMediaService.commit(
                    ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.BANNER, req);

            // then
            assertThat(result.getMediaRole()).isEqualTo("banner");
            then(r2StorageService).should(never()).delete(anyString());
        }

        @Test
        @DisplayName("正常系_ORG_ICON — org の iconUrl が更新される")
        void 正常系_ORG_ICON_iconUrl更新() {
            // given
            String r2Key = "organization/" + ORG_ID + "/icon/uuid.webp";
            ProfileMediaCommitRequest req = buildCommitRequest(r2Key);
            OrganizationEntity mockOrg = OrganizationEntity.builder()
                    .name("テスト組織")
                    .orgType(com.mannschaft.app.organization.entity.OrganizationEntity.OrgType.OTHER)
                    .visibility(com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(com.mannschaft.app.organization.entity.OrganizationEntity.HierarchyVisibility.FULL)
                    .supporterEnabled(false)
                    .build();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(mockOrg));
            given(r2StorageService.generateDownloadUrl(eq(r2Key), any()))
                    .willReturn("https://cdn.example.com/" + r2Key);

            // when
            ProfileMediaResponse result = profileMediaService.commit(
                    ProfileMediaScope.ORGANIZATION, ORG_ID, ProfileMediaRole.ICON, req);

            // then
            assertThat(result.getMediaRole()).isEqualTo("icon");
            then(r2StorageService).should(never()).delete(anyString());
        }

        @Test
        @DisplayName("異常系_r2Key_プレフィックス不一致 — 403")
        void 異常系_r2Key_プレフィックス不一致_403() {
            // given: 別スコープのキーを渡す（user/99 → team/10 に commit しようとする）
            String wrongKey = "user/99/icon/uuid.jpg";
            ProfileMediaCommitRequest req = buildCommitRequest(wrongKey);

            // when / then
            assertThatThrownBy(() -> profileMediaService.commit(
                    ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.ICON, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(r2StorageService).should(never()).delete(anyString());
        }

        @Test
        @DisplayName("異常系_TEAM_存在しない — 404")
        void 異常系_TEAM_存在しない_commit_404() {
            // given
            String r2Key = "team/" + TEAM_ID + "/icon/uuid.jpg";
            ProfileMediaCommitRequest req = buildCommitRequest(r2Key);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> profileMediaService.commit(
                    ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.ICON, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("正常系_ORG_BANNER — org の bannerUrl が更新される")
        void 正常系_ORG_BANNER_bannerUrl更新() {
            // given
            String r2Key = "organization/" + ORG_ID + "/banner/uuid.png";
            ProfileMediaCommitRequest req = buildCommitRequest(r2Key);
            OrganizationEntity mockOrg = OrganizationEntity.builder()
                    .name("テスト組織")
                    .orgType(com.mannschaft.app.organization.entity.OrganizationEntity.OrgType.NPO)
                    .visibility(com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(com.mannschaft.app.organization.entity.OrganizationEntity.HierarchyVisibility.BASIC)
                    .supporterEnabled(false)
                    .build();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(mockOrg));
            given(r2StorageService.generateDownloadUrl(eq(r2Key), any()))
                    .willReturn("https://cdn.example.com/" + r2Key);

            // when
            ProfileMediaResponse result = profileMediaService.commit(
                    ProfileMediaScope.ORGANIZATION, ORG_ID, ProfileMediaRole.BANNER, req);

            // then
            assertThat(result.getMediaRole()).isEqualTo("banner");
            then(r2StorageService).should(never()).delete(anyString());
        }
    }

    // ==================== delete ====================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系_ICON削除_R2も削除される")
        void 正常系_ICON削除_R2も削除される() {
            // given: avatarUrl に既存キーがある
            String existingKey = "user/" + USER_ID + "/icon/existing.jpg";
            UserEntity mockUser = UserEntity.builder()
                    .email("test@example.com")
                    .lastName("テスト")
                    .firstName("ユーザー")
                    .displayName("テストユーザー")
                    .avatarUrl(existingKey)
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(mockUser));

            // when
            profileMediaService.delete(ProfileMediaScope.USER, USER_ID, ProfileMediaRole.ICON, USER_ID);

            // then: R2 から削除される
            then(r2StorageService).should().delete(existingKey);
        }

        @Test
        @DisplayName("正常系_null_の場合は何もしない（冪等）")
        void 正常系_null_の場合は何もしない() {
            // given: avatarUrl が null
            UserEntity mockUser = UserEntity.builder()
                    .email("test@example.com")
                    .lastName("テスト")
                    .firstName("ユーザー")
                    .displayName("テストユーザー")
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(mockUser));

            // when: 例外は発生しない（冪等）
            profileMediaService.delete(ProfileMediaScope.USER, USER_ID, ProfileMediaRole.ICON, USER_ID);

            // then: R2 削除は呼ばれない
            then(r2StorageService).should(never()).delete(anyString());
        }

        @Test
        @DisplayName("正常系_TEAM_BANNER削除_R2も削除される")
        void 正常系_TEAM_BANNER削除_R2も削除される() {
            // given: bannerUrl に既存キーがある
            String existingKey = "team/" + TEAM_ID + "/banner/existing.png";
            TeamEntity mockTeam = TeamEntity.builder()
                    .name("テストチーム")
                    .visibility(com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .bannerUrl(existingKey)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(mockTeam));

            // when
            profileMediaService.delete(ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.BANNER, USER_ID);

            // then: R2 から削除される
            then(r2StorageService).should().delete(existingKey);
        }

        @Test
        @DisplayName("正常系_R2削除失敗でもDB更新は続行")
        void 正常系_R2削除失敗でもDB更新は続行() {
            // given
            String existingKey = "team/" + TEAM_ID + "/icon/existing.jpg";
            TeamEntity mockTeam = TeamEntity.builder()
                    .name("テストチーム")
                    .visibility(com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .iconUrl(existingKey)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(mockTeam));
            willThrow(new RuntimeException("R2接続エラー"))
                    .given(r2StorageService).delete(existingKey);

            // when: R2 削除が失敗しても例外は伝播しない
            profileMediaService.delete(ProfileMediaScope.TEAM, TEAM_ID, ProfileMediaRole.ICON, USER_ID);

            // then: R2 削除は試みられた（ログ出力のみで続行）
            then(r2StorageService).should().delete(existingKey);
        }
    }
}
