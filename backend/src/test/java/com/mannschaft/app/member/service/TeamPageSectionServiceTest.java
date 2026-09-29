package com.mannschaft.app.member.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.dto.CreateSectionRequest;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPageSectionService 単体テスト")
class TeamPageSectionServiceTest {

    @Mock private TeamPageSectionRepository sectionRepository;
    @Mock private TeamPageService pageService;
    @Mock private MemberMapper memberMapper;
    @InjectMocks private TeamPageSectionService service;

    @Nested
    @DisplayName("createSection")
    class CreateSection {

        @Test
        @DisplayName("正常系: セクションが作成される")
        void 作成_正常_保存() {
            // Given
            given(sectionRepository.save(any(TeamPageSectionEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(memberMapper.toSectionResponse(any(TeamPageSectionEntity.class)))
                    .willReturn(new SectionResponse(1L, 1L, "HEADING", "見出し", null, null, null, 0, null, null));

            CreateSectionRequest req = new CreateSectionRequest("HEADING", "見出し", null, null, null, null);

            // When
            SectionResponse result = service.createSection(999L, 1L, req);

            // Then
            assertThat(result.getTitle()).isEqualTo("見出し");
            verify(sectionRepository).save(any(TeamPageSectionEntity.class));
        }
    }

    @Nested
    @DisplayName("deleteSection")
    class DeleteSection {

        @Test
        @DisplayName("異常系: セクション不在でMEMBER_002例外")
        void 削除_不在_例外() {
            // Given
            given(sectionRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteSection(999L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_002"));
        }
    }

    @Nested
    @DisplayName("PR #3387 試練: listSections（V2）の配線")
    class ListSectionsWiring {

        @Test
        @DisplayName("AC-14b(V2): listSections は checkPageViewableOrNotFound を1回呼ぶ")
        void AC14b_V2は閲覧判定を呼ぶ() {
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(10L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(List.of())).willReturn(List.of());

            service.listSections(999L, 10L);

            PageAuthzProbe.verifyCalled(pageService, times(1), PageAuthzProbe.VIEWABLE, 999L, page);
        }

        @Test
        @DisplayName("AC-14b(V2): listSections は checkPageMembershipOrNotFound を呼ばない（閲覧は閲覧用の判定へ）")
        void AC14b_V2は会員確認を呼ばない() {
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(10L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(List.of())).willReturn(List.of());

            service.listSections(999L, 10L);

            PageAuthzProbe.verifyCalled(pageService, never(), PageAuthzProbe.MEMBERSHIP, 999L, page);
        }

        @Test
        @DisplayName("AC-28(V2): セクション0件なら 200 で空配列")
        void AC28_V2_セクション0件_空配列() {
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(10L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(List.of())).willReturn(List.of());

            List<SectionResponse> result = service.listSections(999L, 10L);

            assertThat(result).isEmpty();
        }
    }
}
