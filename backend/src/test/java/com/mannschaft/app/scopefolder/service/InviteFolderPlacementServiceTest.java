package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.scopefolder.dto.InviteFolderCheck;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link InviteFolderPlacementService} の単体テスト（CMP-260826-1457）。
 * スコープ種別の文字列→列挙の変換と、INVITE / DEFAULT の配置先の選択を固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteFolderPlacementService 単体テスト")
class InviteFolderPlacementServiceTest {

    @Mock
    private MyScopeFolderService myScopeFolderService;

    @InjectMocks
    private InviteFolderPlacementService service;

    private static final Long USER_ID = 1L;
    private static final Long FOLDER_ID = 10L;
    private static final Long SCOPE_ID = 20L;

    @Test
    @DisplayName("checkInviteFolder: TEAM / ORGANIZATION の文字列を列挙へ変換して事前確認に委譲する")
    void checkInviteFolder() {
        given(myScopeFolderService.inspectInviteFolder(USER_ID, FOLDER_ID, ScopeType.TEAM))
                .willReturn(InviteFolderCheck.OK);
        given(myScopeFolderService.inspectInviteFolder(USER_ID, FOLDER_ID, ScopeType.ORGANIZATION))
                .willReturn(InviteFolderCheck.TYPE_MISMATCH);

        assertThat(service.checkInviteFolder(USER_ID, FOLDER_ID, "TEAM")).isEqualTo(InviteFolderCheck.OK);
        assertThat(service.checkInviteFolder(USER_ID, FOLDER_ID, "ORGANIZATION"))
                .isEqualTo(InviteFolderCheck.TYPE_MISMATCH);
    }

    @Test
    @DisplayName("place: folderId 指定時は当該フォルダへ INVITE で配置する")
    void placeIntoChosenFolder() {
        service.place(USER_ID, FOLDER_ID, "TEAM", SCOPE_ID);

        verify(myScopeFolderService).addItemWithAssignedVia(USER_ID, FOLDER_ID, SCOPE_ID, AssignedVia.INVITE);
    }

    @Test
    @DisplayName("place: 未指定時は未分類フォルダを取得（無ければ生成）して DEFAULT で配置する")
    void placeIntoDefaultFolder() {
        MyScopeFolderEntity defaultFolder = MyScopeFolderEntity.builder()
                .id(999L).userId(USER_ID).scopeType(ScopeType.ORGANIZATION)
                .name("未分類").isDefault(Boolean.TRUE).sortOrder(9999).build();
        given(myScopeFolderService.findOrCreateDefaultInternal(USER_ID, ScopeType.ORGANIZATION))
                .willReturn(defaultFolder);

        service.place(USER_ID, null, "ORGANIZATION", SCOPE_ID);

        verify(myScopeFolderService).addItemWithAssignedVia(USER_ID, 999L, SCOPE_ID, AssignedVia.DEFAULT);
    }
}
