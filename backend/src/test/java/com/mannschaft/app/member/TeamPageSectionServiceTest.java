package com.mannschaft.app.member;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.dto.CreateSectionRequest;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import com.mannschaft.app.member.service.TeamPageSectionService;
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
            SectionResponse result = service.createSection(1L, req);

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
            assertThatThrownBy(() -> service.deleteSection(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_002"));
        }
    }
}
