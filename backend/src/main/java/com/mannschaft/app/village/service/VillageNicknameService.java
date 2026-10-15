package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageNicknameResponse;
import com.mannschaft.app.village.dto.VillageNicknameUpdateRequest;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F17.1 B4 — 村ニックネーム管理サービス（全村共通 1 つ）。
 *
 * <p>Phase 1 は 1 ユーザー = 1 ニックネーム（{@code villageId IS NULL} の行）で運用する。
 * ニックネームはプラットフォーム全体で一意（先着優先）。</p>
 *
 * <p>レートリミット: 月 3 回まで。{@code change_count_this_month} は月初リセットだが、
 * リセットの実行はバッチ（B11 担当）と独立に、本サービスでは {@code lastChangedAt} の
 * 年月を見て動的に「今月分の使用回数」を判定する。</p>
 *
 * <p>{@code @Transactional} は village ドメイン内に閉じる（原則5 準拠）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageNicknameService {

    /** 月内変更上限（設計書 §6.4） */
    public static final int MONTHLY_CHANGE_LIMIT = 3;

    private static final int NICKNAME_MIN_LENGTH = 2;
    private static final int NICKNAME_MAX_LENGTH = 40;

    /**
     * 禁止語の簡易リスト（Phase 1）。
     * 将来的に共通の NgWordService に差し替え予定（設計書 §10）。
     */
    private static final Set<String> NG_WORDS = Set.of(
            "admin", "administrator", "system", "root", "owner",
            "運営", "管理者", "公式", "official", "support",
            "fuck", "shit", "死ね", "殺す"
    );

    /**
     * 使用可能文字の制限（全角・半角の英数字・かな漢字・スペース・一部記号）。
     * 制御文字や絵文字（サロゲートペア）は弾く。
     */
    private static final Pattern ALLOWED_CHARS = Pattern.compile(
            "^[\\p{L}\\p{N}\\p{Pd}\\p{Pc}\\p{Zs}・！？!?.,_\\-]+$"
    );

    private final UserVillageNicknameRepository nicknameRepository;

    /**
     * 自分の村ニックネーム（全村共通行）を取得する。
     *
     * @param userId 認証済みユーザーID
     * @return ニックネームレスポンス。未設定なら {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<VillageNicknameResponse> getMyNickname(Long userId) {
        return nicknameRepository.findByUserIdAndVillageIdIsNull(userId)
                .map(entity -> toResponse(entity, resolveChangeCountThisMonth(entity)));
    }

    /**
     * 自分の村ニックネームを新規作成または上書きする。
     *
     * <p>動作概要:</p>
     * <ol>
     *   <li>入力バリデーション（長さ・使用文字・NG ワード）</li>
     *   <li>レートリミット判定（月 3 回まで）— ただし「同一ニックネームへの no-op 更新」はカウントしない</li>
     *   <li>グローバル UNIQUE のアプリ層先チェック（自分自身を除く）</li>
     *   <li>保存。DB UNIQUE 制約とぶつかれば {@link DataIntegrityViolationException} を 409 に変換</li>
     * </ol>
     *
     * @param userId  認証済みユーザーID
     * @param request 更新リクエスト
     * @return 更新後のニックネームレスポンス
     */
    @Transactional
    public VillageNicknameResponse updateMyNickname(Long userId, VillageNicknameUpdateRequest request) {
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        validateNickname(nickname);

        Optional<UserVillageNicknameEntity> existing = nicknameRepository.findByUserIdAndVillageIdIsNull(userId);

        // 同一ニックネームのまま avatar/bio だけ更新する場合は change_count を増やさない
        boolean nicknameChanged = existing.map(e -> !Objects.equals(e.getNickname(), nickname))
                .orElse(true);

        if (nicknameChanged) {
            // レートリミット: 同一月内の変更回数で判定
            long usedThisMonth = existing.map(this::resolveChangeCountThisMonth).orElse(0L);
            if (usedThisMonth >= MONTHLY_CHANGE_LIMIT) {
                log.info("村ニックネーム変更レート超過: userId={}, used={}", userId, usedThisMonth);
                throw new BusinessException(VillageErrorCode.NICKNAME_CHANGE_THROTTLED);
            }

            // グローバル UNIQUE のアプリ層先チェック（自分自身が同名の場合は通る = 上の早期判定で no-op 化済み）
            if (nicknameRepository.existsByNickname(nickname)) {
                throw new BusinessException(VillageErrorCode.NICKNAME_TAKEN);
            }
        }

        UserVillageNicknameEntity entity = existing.orElseGet(() -> UserVillageNicknameEntity.builder()
                .userId(userId)
                .villageId(null) // Phase 1: 全村共通
                .changeCountThisMonth(0L)
                .build());

        entity.setNickname(nickname);
        entity.setAvatarR2Key(request.avatarR2Key());
        entity.setBio(request.bio());

        if (nicknameChanged) {
            // 月跨ぎなら 1 へリセット、同月なら + 1
            long usedThisMonth = resolveChangeCountThisMonth(entity);
            entity.setChangeCountThisMonth(usedThisMonth + 1);
            entity.setLastChangedAt(LocalDateTime.now());
        }

        try {
            UserVillageNicknameEntity saved = nicknameRepository.saveAndFlush(entity);
            return toResponse(saved, resolveChangeCountThisMonth(saved));
        } catch (DataIntegrityViolationException ex) {
            // 同名で 2 ユーザーが同時 PUT したケース（UNIQUE 制約衝突）
            log.info("村ニックネーム UNIQUE 衝突 (race): userId={}, nickname={}", userId, nickname);
            throw new BusinessException(VillageErrorCode.NICKNAME_TAKEN, ex);
        }
    }

    /**
     * ニックネームのバリデーション。
     * 違反時は {@link VillageErrorCode#NICKNAME_INVALID} を投げる。
     */
    private void validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new BusinessException(VillageErrorCode.NICKNAME_INVALID);
        }
        int length = nickname.codePointCount(0, nickname.length());
        if (length < NICKNAME_MIN_LENGTH || length > NICKNAME_MAX_LENGTH) {
            throw new BusinessException(VillageErrorCode.NICKNAME_INVALID);
        }
        if (!ALLOWED_CHARS.matcher(nickname).matches()) {
            throw new BusinessException(VillageErrorCode.NICKNAME_INVALID);
        }
        String lower = nickname.toLowerCase();
        for (String ng : NG_WORDS) {
            if (lower.contains(ng.toLowerCase())) {
                throw new BusinessException(VillageErrorCode.NICKNAME_INVALID);
            }
        }
    }

    /**
     * エンティティの {@code lastChangedAt} を見て「今月分の使用回数」を判定する。
     * 月跨ぎなら 0 として扱う（B11 バッチが物理的にゼロクリアするのを待たずに済むため）。
     */
    private long resolveChangeCountThisMonth(UserVillageNicknameEntity entity) {
        LocalDateTime last = entity.getLastChangedAt();
        Long count = entity.getChangeCountThisMonth();
        if (last == null || count == null) {
            return 0L;
        }
        YearMonth lastMonth = YearMonth.from(last);
        YearMonth currentMonth = YearMonth.now();
        if (!lastMonth.equals(currentMonth)) {
            return 0L;
        }
        return count;
    }

    private VillageNicknameResponse toResponse(UserVillageNicknameEntity entity, long changeCountThisMonth) {
        return VillageNicknameResponse.builder()
                .nickname(entity.getNickname())
                .avatarR2Key(entity.getAvatarR2Key())
                .bio(entity.getBio())
                .lastChangedAt(entity.getLastChangedAt())
                .changeCountThisMonth(changeCountThisMonth)
                .monthlyLimit(MONTHLY_CHANGE_LIMIT)
                .build();
    }

    /**
     * 参考実装（B11 バッチ担当）: ユーザー退会時の物理削除。
     * 実装は B11 バッチで {@code deleteAllByUserId} を Repository に追加する想定。
     * ここではコメントとして配置する。
     *
     * <pre>
     * void deletePersonalDataOnAccountClosure(Long userId) {
     *     nicknameRepository.deleteAllByUserId(userId);  // 物理削除（個人情報）
     * }
     * </pre>
     */
    @SuppressWarnings("unused")
    private static final String DELETE_PERSONAL_DATA_REFERENCE = "implemented by B11 batch";

    /** 暫定ヘルパ: 単体テストでバッチ等価動作を確認するための未公開 API は持たない方針。 */
    @SuppressWarnings("unused")
    private static List<String> reservedForBatchHandoff() {
        return List.of();
    }
}
