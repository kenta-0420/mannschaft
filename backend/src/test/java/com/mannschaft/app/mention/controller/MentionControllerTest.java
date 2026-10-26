package com.mannschaft.app.mention.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.mention.dto.MentionResponse;
import com.mannschaft.app.mention.service.MentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MentionController} の単体テスト。
 *
 * <p>MentionController#getMentions の自己スコープ性を固定する契約テスト。
 * {@code mentionService.getMentions} は {@code SecurityUtils.getCurrentUserId()} のみを
 * 検索条件に束縛するため、他人宛のメンションへ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MentionController 単体テスト")
class MentionControllerTest {

    @Mock
    private MentionService mentionService;

    @InjectMocks
    private MentionController controller;

    private static final Long USER_ID = 100L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("getMentions は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMentions_boundToCurrentUserOnly() {
        given(mentionService.getMentions(USER_ID)).willReturn(List.of());

        ResponseEntity<ApiResponse<List<MentionResponse>>> response = controller.getMentions();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(mentionService).getMentions(USER_ID);
    }
}
