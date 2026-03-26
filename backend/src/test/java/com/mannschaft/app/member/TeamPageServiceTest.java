package com.mannschaft.app.member;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.dto.CreateTeamPageRequest;
import com.mannschaft.app.member.dto.TeamPageResponse;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import com.mannschaft.app.member.service.TeamPageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPageService 単体テスト")
class TeamPageServiceTest {

    @Mock private TeamPageRepository pageRepository;
    @Mock private TeamPageSectionRepository sectionRepository;
    @Mock private MemberProfileRepository profileRepository;
    @Mock private MemberMapper memberMapper;
    @InjectMocks private TeamPageService service;

    @Nested
    @DisplayName("createPage")
    class CreatePage {

        @Test
        @DisplayName("正常系: ページが作成される")
        void 作成_正常_保存() {
            // Given
            CreateTeamPageRequest req = new CreateTeamPageRequest(
                    1L, null, "メンバー紹介", "members", "MAIN", null,
                    null, null, "MEMBERS_ONLY");
            given(pageRepository.findByTeamIdAndPageType(1L, PageType.MAIN)).willReturn(Optional.empty());
            given(pageRepository.existsByTeamIdAndSlug(1L, "members")).willReturn(false);
            given(pageRepository.save(any(TeamPageEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(memberMapper.toTeamPageResponse(any(TeamPageEntity.class)))
                    .willReturn(new TeamPageResponse(1L, 1L, null, "メンバー紹介", "members",
                            "MAIN", null, null, null, "MEMBERS_ONLY", "DRAFT", false, 0, null, null, null, null, null));

            // When
            TeamPageResponse result = service.createPage(100L, req);

            // Then
            assertThat(result.getTitle()).isEqualTo("メンバー紹介");
            verify(pageRepository).save(any(TeamPageEntity.class));
        }

        @Test
        @DisplayName("異常系: メインページ重複でMEMBER_007例外")
        void 作成_メイン重複_例外() {
            // Given
            CreateTeamPageRequest req = new CreateTeamPageRequest(
                    1L, null, "メンバー紹介", "members", "MAIN", null,
                    null, null, null);
            given(pageRepository.findByTeamIdAndPageType(1L, PageType.MAIN))
                    .willReturn(Optional.of(TeamPageEntity.builder().teamId(1L)
                            .title("既存").slug("existing").pageType(PageType.MAIN).build()));

            // When / Then
            assertThatThrownBy(() -> service.createPage(100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_007"));
        }

        @Test
        @DisplayName("異常系: スラッグ重複でMEMBER_005例外")
        void 作成_スラッグ重複_例外() {
            // Given
            CreateTeamPageRequest req = new CreateTeamPageRequest(
                    1L, null, "年度ページ", "members", "YEARLY", (short) 2025,
                    null, null, null);
            given(pageRepository.existsByTeamIdAndSlug(1L, "members")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.createPage(100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_005"));
        }
    }

    @Nested
    @DisplayName("getPage")
    class GetPage {

        @Test
        @DisplayName("異常系: ページ不在でMEMBER_001例外")
        void 取得_不在_例外() {
            // Given
            given(pageRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getPage(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_001"));
        }
    }

    @Nested
    @DisplayName("deletePage")
    class DeletePage {

        @Test
        @DisplayName("正常系: ページが論理削除される")
        void 削除_正常() {
            // Given
            TeamPageEntity entity = TeamPageEntity.builder()
                    .teamId(1L).title("テスト").slug("test").pageType(PageType.MAIN).build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageRepository.save(any(TeamPageEntity.class))).willReturn(entity);

            // When
            service.deletePage(1L);

            // Then
            verify(pageRepository).save(any(TeamPageEntity.class));
        }
    }
}
