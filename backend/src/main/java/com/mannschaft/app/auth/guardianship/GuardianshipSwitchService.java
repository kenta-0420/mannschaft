package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.dto.BlockedChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.family.service.CareLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F08.9 P3a 後見切替の集約サービス（切替可能な子の列挙）。
 *
 * <p>認証ユーザー（保護者・払い手）が後見切替できる子を集約する。子の権原は 2 経路で成立する:</p>
 * <ul>
 *   <li>auth: {@link ParentalConsentService#listApprovedChildUserIds}（parental_consent APPROVED）</li>
 *   <li>family: {@link CareLinkService#listActiveParentWatchedRecipientIds}（care_links ACTIVE PARENT）</li>
 * </ul>
 *
 * <p>各ドメインの Service が返す<b>ユーザーID のみ</b>を受け取り（Entity 直接参照禁止・ドメイン境界遵守）、
 * その和集合に対して年齢ポリシー（{@link GuardianshipAgePolicyRegistry}）で切替可否を判定する。
 * 子の生年月日・国コードは {@link UserEntity}（暗号化 {@code birthDate}）を都度復号して解決する。</p>
 *
 * <p><b>TODO（将来最適化）</b>: 03_security §3.1 のとおり、年齢段階判定は本来 @Scheduled バッチで
 * 事前算出（{@code switchAllowed}/{@code stageKey} のスナップショット）し、ホットパスで復号を持ち回らない設計。
 * 本実装は MVP として都度復号で算出する。境界日（年度末・誕生日）の再計算バッチは別フェーズで実装する。</p>
 *
 * <p>閲覧者は常に「自分（認証ユーザー）」のみ。他人の保護者一覧を覗く経路は提供しない（IDOR 防止）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GuardianshipSwitchService {

    /** 封印理由の i18n ラベルキー（年齢到達による封印）。 */
    private static final String REASON_AGE_LOCKED = "AGE_LOCKED";

    private final ParentalConsentService parentalConsentService;
    private final CareLinkService careLinkService;
    private final UserRepository userRepository;
    private final GuardianshipAgePolicyRegistry agePolicyRegistry;
    private final Clock clock;

    /**
     * 認証ユーザー（保護者）の切替可能な子・封印された子を集約して返す。
     *
     * @param guardianUserId 保護者（払い手＝閲覧者本人）のユーザーID
     * @return 切替可能な子（{@code children}）と封印された子（{@code blockedChildren}）
     */
    public SwitchableChildrenResponse listSwitchableChildren(Long guardianUserId) {
        // 2 経路の権原から候補子IDを和集合化（重複排除・順序保持）。
        Set<Long> childUserIds = new LinkedHashSet<>();
        childUserIds.addAll(parentalConsentService.listApprovedChildUserIds(guardianUserId));
        childUserIds.addAll(careLinkService.listActiveParentWatchedRecipientIds(guardianUserId));

        // 自分自身を子として扱わない（防御的・データ不整合対策）。
        childUserIds.remove(guardianUserId);

        if (childUserIds.isEmpty()) {
            return new SwitchableChildrenResponse(List.of(), List.of());
        }

        // 子の属性（表示名・暗号化 birthDate・国コード）を一括ロード（N+1 防止）。
        List<UserEntity> childUsers = userRepository.findByIdIn(childUserIds);

        List<SwitchableChildDto> switchable = new ArrayList<>();
        List<BlockedChildDto> blocked = new ArrayList<>();

        for (UserEntity child : childUsers) {
            LocalDate birthDate = parseBirthDate(child);
            if (birthDate == null) {
                // 生年月日が無い／復号不能な子は安全側で封印に倒す（症状を隠さず記録）。
                log.warn("後見切替: 子 userId={} の birthDate が解決できないため切替を封印（安全側）", child.getId());
                blocked.add(new BlockedChildDto(
                        child.getId(), child.getDisplayName(), "unknown", false, REASON_AGE_LOCKED));
                continue;
            }

            GuardianshipAgePolicy policy = agePolicyRegistry.forCountry(child.getCountryCode());
            AgeStageResolution resolution = policy.resolve(birthDate, clock);

            if (resolution.switchAllowed()) {
                switchable.add(new SwitchableChildDto(
                        child.getId(), child.getDisplayName(), resolution.stageKey(), true));
            } else {
                blocked.add(new BlockedChildDto(
                        child.getId(), child.getDisplayName(), resolution.stageKey(), false, REASON_AGE_LOCKED));
            }
        }

        return new SwitchableChildrenResponse(switchable, blocked);
    }

    /**
     * 子の暗号化 birthDate（復号済み文字列・ISO-8601）を {@link LocalDate} へパースする。
     * 値が無い／不正フォーマットの場合は {@code null} を返す（呼び出し側で安全側に封印）。
     */
    private LocalDate parseBirthDate(UserEntity child) {
        String raw = child.getBirthDate();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("後見切替: 子 userId={} の birthDate パースに失敗（不正フォーマット）", child.getId());
            return null;
        }
    }
}
