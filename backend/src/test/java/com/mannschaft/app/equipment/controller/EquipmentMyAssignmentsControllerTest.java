package com.mannschaft.app.equipment.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.equipment.dto.AssignmentResponse;
import com.mannschaft.app.equipment.service.EquipmentAssignmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link EquipmentMyAssignmentsController} の単体テスト（自己スコープ契約テストを兼ねる・認可根治戦役 Wave6 ロットC）。
 *
 * <p>{@code EquipmentAssignmentService#getMyAssignments} には常に認証主体の {@code USER_ID} のみが渡り、
 * 全チーム・組織横断であっても他ユーザーの貸出は含まれないことを固定する。
 * {@code EquipmentMyAssignmentsController#getMyAssignments} の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EquipmentMyAssignmentsController 単体テスト")
class EquipmentMyAssignmentsControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private EquipmentAssignmentService assignmentService;

    @InjectMocks
    private EquipmentMyAssignmentsController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getMyAssignments: 認証主体自身の userId のみで全チーム・組織横断に検索する"
            + "（EquipmentMyAssignmentsController#getMyAssignments）")
    void getMyAssignments_自己スコープ() {
        AssignmentResponse item = new AssignmentResponse(1L, 2L, "ノートPC", USER_ID, "テスト太郎",
                1, LocalDateTime.now(), null, null, null);
        given(assignmentService.getMyAssignments(eq(USER_ID), eq(PageRequest.of(0, 20))))
                .willReturn(new PageImpl<>(List.of(item)));

        PagedResponse<AssignmentResponse> result = controller.getMyAssignments(0, 20).getBody();

        assertThat(result).isNotNull();
        assertThat(result.getData()).hasSize(1);
        verify(assignmentService).getMyAssignments(USER_ID, PageRequest.of(0, 20));
    }
}
