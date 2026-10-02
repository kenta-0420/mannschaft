package com.mannschaft.app.scopefolder;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.service.ScopeFolderAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScopeFolderAccessGuard} の単体テスト（認可根治 Wave4 ロットC）。
 *
 * <p>判定の材料はパス変数のフォルダ ID ではなく、取得済みフォルダの {@code user_id} である。
 * 不存在と他者所有が同一のエラーに畳まれること（存在の観測を許さないこと）も固定する。</p>
 */
@DisplayName("ScopeFolderAccessGuard 単体テスト（認可根治 Wave4 ロットC）")
class ScopeFolderAccessGuardTest {

    private final ScopeFolderAccessGuard guard = new ScopeFolderAccessGuard();

    private static final Long OWNER_ID = 1L;
    private static final Long FOREIGN_USER_ID = 900_000_001L;

    private MyScopeFolderEntity folder(Long userId) {
        return MyScopeFolderEntity.builder()
                .id(10L)
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .name("フォルダ")
                .isDefault(Boolean.FALSE)
                .sortOrder(0)
                .build();
    }

    @Test
    @DisplayName("所有者本人のフォルダはそのまま返る")
    void 所有者本人なら通る() {
        MyScopeFolderEntity own = folder(OWNER_ID);
        assertThat(guard.requireOwnedFolder(own, OWNER_ID)).isSameAs(own);
    }

    @Test
    @DisplayName("他ユーザー所有のフォルダは SCOPE_FOLDER_NOT_FOUND で拒否される")
    void 他ユーザー所有は拒否される() {
        assertThatThrownBy(() -> guard.requireOwnedFolder(folder(OWNER_ID), FOREIGN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND);
    }

    @Test
    @DisplayName("フォルダが取得できない場合も同一のエラーになる（存在秘匿・fail-closed）")
    void 不存在も同一エラー() {
        assertThatThrownBy(() -> guard.requireOwnedFolder(null, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND);
    }

    @Test
    @DisplayName("操作者が特定できない場合は拒否される")
    void 操作者不明は拒否される() {
        assertThatThrownBy(() -> guard.requireOwnedFolder(folder(OWNER_ID), null))
                .isInstanceOf(BusinessException.class);
    }
}
