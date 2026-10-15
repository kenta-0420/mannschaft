package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageNicknameResponse;
import com.mannschaft.app.village.dto.VillageNicknameUpdateRequest;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageNicknameService} 単体テスト（F17.1 B4）。
 *
 * <p>設計書 §3.5 / §4.5 / §6.4 に従い以下を検証:</p>
 * <ul>
 *   <li>新規作成 / 更新 / 同名 no-op</li>
 *   <li>グローバル UNIQUE 衝突（アプリ層先チェック + DB 競合）</li>
 *   <li>NG ワード / 長さ / 使用文字バリデーション</li>
 *   <li>レートリミット（月 3 回まで OK / 4 回目で 429）</li>
 *   <li>月跨ぎ動的リセット</li>
 *   <li>空文字 / 最大長</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageNicknameService 単体テスト")
class VillageNicknameServiceTest {

    @Mock
    private UserVillageNicknameRepository nicknameRepository;

    @InjectMocks
    private VillageNicknameService nicknameService;

    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("新規作成: 既存行なし → INSERT、change_count=1 になる")
    void updateMyNickname_create() {
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(nicknameRepository.existsByNickname("たまねぎ侍")).willReturn(false);
        given(nicknameRepository.saveAndFlush(any(UserVillageNicknameEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        VillageNicknameUpdateRequest req = new VillageNicknameUpdateRequest("たまねぎ侍", "k", "玉ねぎが好き");
        VillageNicknameResponse res = nicknameService.updateMyNickname(USER_ID, req);

        ArgumentCaptor<UserVillageNicknameEntity> captor =
                ArgumentCaptor.forClass(UserVillageNicknameEntity.class);
        verify(nicknameRepository).saveAndFlush(captor.capture());
        UserVillageNicknameEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getVillageId()).isNull();
        assertThat(saved.getNickname()).isEqualTo("たまねぎ侍");
        assertThat(saved.getChangeCountThisMonth()).isEqualTo(1L);
        assertThat(res.changeCountThisMonth()).isEqualTo(1L);
        assertThat(res.monthlyLimit()).isEqualTo(3);
    }

    @Test
    @DisplayName("更新（別名）: 既存行あり → ニックネーム差し替え、change_count を +1")
    void updateMyNickname_renameIncrementsCounter() {
        UserVillageNicknameEntity existing = baseEntity("旧名", LocalDateTime.now().withDayOfMonth(1), 1L);
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.of(existing));
        given(nicknameRepository.existsByNickname("新名")).willReturn(false);
        given(nicknameRepository.saveAndFlush(any(UserVillageNicknameEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        VillageNicknameResponse res = nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("新名", null, null));

        assertThat(res.nickname()).isEqualTo("新名");
        assertThat(res.changeCountThisMonth()).isEqualTo(2L);
    }

    @Test
    @DisplayName("同名 no-op: ニックネーム不変なら change_count を増やさない・UNIQUE チェックもしない")
    void updateMyNickname_sameNicknameDoesNotIncrement() {
        UserVillageNicknameEntity existing = baseEntity("たまねぎ侍", LocalDateTime.now(), 1L);
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.of(existing));
        given(nicknameRepository.saveAndFlush(any(UserVillageNicknameEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        VillageNicknameResponse res = nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("たまねぎ侍", "newkey", "bio更新"));

        // existsByNickname を呼ばない
        verify(nicknameRepository, never()).existsByNickname(any());
        assertThat(res.changeCountThisMonth()).isEqualTo(1L);
        assertThat(res.avatarR2Key()).isEqualTo("newkey");
        assertThat(res.bio()).isEqualTo("bio更新");
    }

    @Test
    @DisplayName("グローバル UNIQUE 衝突（アプリ層先チェック）: 別ユーザーが同名を使用済み → 409 NICKNAME_TAKEN")
    void updateMyNickname_nicknameTaken_preCheck() {
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(nicknameRepository.existsByNickname("ゆうしゃ")).willReturn(true);

        assertThatThrownBy(() -> nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("ゆうしゃ", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NICKNAME_TAKEN);

        verify(nicknameRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("DB UNIQUE 競合（saveAndFlush）: 同名で 2 ユーザーが同時 PUT → DataIntegrityViolation を 409 に変換")
    void updateMyNickname_nicknameTaken_dbRace() {
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(nicknameRepository.existsByNickname("おむすび")).willReturn(false);
        given(nicknameRepository.saveAndFlush(any(UserVillageNicknameEntity.class)))
                .willThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("おむすび", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NICKNAME_TAKEN);
    }

    @Test
    @DisplayName("NG ワード: 'admin' を含む → 422 NICKNAME_INVALID")
    void updateMyNickname_ngWord() {
        assertThatThrownBy(() -> nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("super_admin", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NICKNAME_INVALID);

        verify(nicknameRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("長さバリデーション: 1 文字 → 422、41 文字 → 422")
    void updateMyNickname_lengthValidation() {
        assertThatThrownBy(() -> nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("a", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NICKNAME_INVALID);

        String tooLong = "a".repeat(41);
        assertThatThrownBy(() -> nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest(tooLong, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NICKNAME_INVALID);
    }

    @Test
    @DisplayName("レートリミット: 月3回まで OK、4 回目で 429 NICKNAME_CHANGE_THROTTLED")
    void updateMyNickname_rateLimit() {
        // 既に当月3回変更済み
        UserVillageNicknameEntity existing = baseEntity("name3", LocalDateTime.now(), 3L);
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("name4", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NICKNAME_CHANGE_THROTTLED);

        verify(nicknameRepository, never()).existsByNickname(any());
        verify(nicknameRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("月跨ぎリセット: 先月の change_count=3 でも今月分は 0 として扱い、変更を許可する")
    void updateMyNickname_monthRolloverResets() {
        LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1);
        UserVillageNicknameEntity existing = baseEntity("先月名", lastMonth, 3L);
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.of(existing));
        given(nicknameRepository.existsByNickname("今月名")).willReturn(false);
        given(nicknameRepository.saveAndFlush(any(UserVillageNicknameEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        VillageNicknameResponse res = nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("今月名", null, null));

        // 先月3回 → 今月にまたいだので 0 として扱い、+1 で 1 になる
        assertThat(res.changeCountThisMonth()).isEqualTo(1L);
    }

    @Test
    @DisplayName("空文字: 422 NICKNAME_INVALID")
    void updateMyNickname_blank() {
        assertThatThrownBy(() -> nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest("   ", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NICKNAME_INVALID);
    }

    @Test
    @DisplayName("最大長 40 文字: 成功（境界値）")
    void updateMyNickname_maxLengthOk() {
        String exact40 = "a".repeat(40);
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(nicknameRepository.existsByNickname(exact40)).willReturn(false);
        given(nicknameRepository.saveAndFlush(any(UserVillageNicknameEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        VillageNicknameResponse res = nicknameService.updateMyNickname(USER_ID,
                new VillageNicknameUpdateRequest(exact40, null, null));

        assertThat(res.nickname()).hasSize(40);
        assertThat(res.changeCountThisMonth()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getMyNickname: 未設定なら Optional.empty を返す")
    void getMyNickname_empty() {
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.empty());

        assertThat(nicknameService.getMyNickname(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("getMyNickname: 設定あり + 月跨ぎなら changeCountThisMonth=0 を返す（動的リセット）")
    void getMyNickname_monthRolloverDynamic() {
        LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1);
        UserVillageNicknameEntity existing = baseEntity("先月名", lastMonth, 3L);
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.of(existing));

        Optional<VillageNicknameResponse> res = nicknameService.getMyNickname(USER_ID);

        assertThat(res).isPresent();
        assertThat(res.get().changeCountThisMonth()).isEqualTo(0L);
        assertThat(res.get().monthlyLimit()).isEqualTo(3);
    }

    private UserVillageNicknameEntity baseEntity(String nickname, LocalDateTime lastChangedAt, long count) {
        return UserVillageNicknameEntity.builder()
                .userId(USER_ID)
                .villageId(null)
                .nickname(nickname)
                .avatarR2Key(null)
                .bio(null)
                .lastChangedAt(lastChangedAt)
                .changeCountThisMonth(count)
                .createdAt(lastChangedAt)
                .updatedAt(lastChangedAt)
                .build();
    }
}
