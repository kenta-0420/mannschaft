package com.mannschaft.app.matching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.CreateNgTeamRequest;
import com.mannschaft.app.matching.dto.NgTeamResponse;
import com.mannschaft.app.matching.entity.NgTeamEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import com.mannschaft.app.matching.service.NgTeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link NgTeamService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NgTeamService 単体テスト")
class NgTeamServiceTest {

    @Mock
    private NgTeamRepository ngTeamRepository;
    @Mock
    private MatchingMapper matchingMapper;

    @InjectMocks
    private NgTeamService service;

    private static final Long TEAM_ID = 1L;
    private static final Long BLOCKED_TEAM_ID = 2L;

    @Nested
    @DisplayName("addNgTeam")
    class AddNgTeam {

        @Test
        @DisplayName("異常系: 自チームをNG設定不可")
        void 自チームNG設定不可() {
            CreateNgTeamRequest request = new CreateNgTeamRequest(TEAM_ID, "テスト");

            assertThatThrownBy(() -> service.addNgTeam(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.SELF_NG_NOT_ALLOWED);
        }

        @Test
        @DisplayName("異常系: 重複NG設定エラー")
        void 重複NG設定() {
            given(ngTeamRepository.existsByTeamIdAndBlockedTeamId(TEAM_ID, BLOCKED_TEAM_ID)).willReturn(true);
            CreateNgTeamRequest request = new CreateNgTeamRequest(BLOCKED_TEAM_ID, "テスト");

            assertThatThrownBy(() -> service.addNgTeam(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.DUPLICATE_NG_TEAM);
        }

        @Test
        @DisplayName("正常系: NGチーム追加成功")
        void NGチーム追加成功() {
            given(ngTeamRepository.existsByTeamIdAndBlockedTeamId(TEAM_ID, BLOCKED_TEAM_ID)).willReturn(false);
            NgTeamEntity saved = NgTeamEntity.builder().teamId(TEAM_ID).blockedTeamId(BLOCKED_TEAM_ID).build();
            given(ngTeamRepository.save(any())).willReturn(saved);
            given(matchingMapper.toNgTeamResponse(saved)).willReturn(new NgTeamResponse(BLOCKED_TEAM_ID, null));

            CreateNgTeamRequest request = new CreateNgTeamRequest(BLOCKED_TEAM_ID, "マナー違反");
            service.addNgTeam(TEAM_ID, request);

            verify(ngTeamRepository).save(any());
        }
    }

    @Nested
    @DisplayName("removeNgTeam")
    class RemoveNgTeam {

        @Test
        @DisplayName("異常系: NG設定が見つからない")
        void NG設定不存在() {
            given(ngTeamRepository.findByTeamIdAndBlockedTeamId(TEAM_ID, BLOCKED_TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeNgTeam(TEAM_ID, BLOCKED_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.NG_TEAM_NOT_FOUND);
        }
    }
}
