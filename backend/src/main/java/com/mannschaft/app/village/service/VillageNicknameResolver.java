package com.mannschaft.app.village.service;

import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 村人の表示名を村ニックネームで解決する共有ヘルパ（F17.3 前工程リファクタ・設計書 §5.2/§15.4）。
 *
 * <p>従来 {@code VillageMeetupService}／{@code VillageCalendarService}／
 * {@code VillageMatchRecruitService} にバイト同型の private {@code resolveUserDisplayName} が
 * 三重に複製されていた（重複ドリフトの温床）。表示名解決という「村ドメインの共通述語」を
 * この一箇所へ寄せることで、実装ドリフトを構造的に防ぐ（memory
 * {@code feedback_mapstruct_implicit_string_method} 系の複製ドリフト回避）。</p>
 *
 * <h2>解決順（実名スナップショット禁止・§10 G4）</h2>
 * <ol>
 *   <li>村内ニックネーム（{@code findByUserIdAndVillageId}・{@code villageId} 指定時のみ）</li>
 *   <li>全村共通ニックネーム（{@code findByUserIdAndVillageIdIsNull}）</li>
 *   <li>いずれも無ければ {@code "USER:#{userId}"} プレースホルダ</li>
 * </ol>
 *
 * <p>{@code userId} が {@code null} の場合は {@code null} を返す（呼び出し側の null 表示を維持）。
 * 抽出はふるまい完全不変のリファクタで、既存 3 サービスの単票版はいずれも本メソッドへ委譲する。</p>
 */
@Component
@RequiredArgsConstructor
public class VillageNicknameResolver {

    private final UserVillageNicknameRepository nicknameRepository;

    /**
     * ユーザー ID を村ニックネームで表示名解決する（村内 → 全村共通 → {@code "USER:#id"}）。
     *
     * @param userId    ユーザー ID（{@code null} なら {@code null} を返す）
     * @param villageId 村 ID（{@code null} なら全村共通ニックネームのみ参照）
     * @return 解決した表示名（実名は含まない）／{@code userId} が {@code null} なら {@code null}
     */
    public String resolve(Long userId, UUID villageId) {
        if (userId == null) {
            return null;
        }
        if (villageId != null) {
            Optional<UserVillageNicknameEntity> villageNick =
                    nicknameRepository.findByUserIdAndVillageId(userId, villageId);
            if (villageNick.isPresent()) {
                return villageNick.get().getNickname();
            }
        }
        return nicknameRepository.findByUserIdAndVillageIdIsNull(userId)
                .map(UserVillageNicknameEntity::getNickname)
                .orElse("USER:#" + userId);
    }
}
