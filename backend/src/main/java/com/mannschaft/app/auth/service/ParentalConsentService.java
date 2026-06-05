package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.util.SecureTokenGenerator;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.util.AgeGroupCalculator;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意管理サービス。
 *
 * <p>未成年ユーザーの保護者招待・承認・拒否・失効のライフサイクル全体を管理する。
 * クロスドメイン FK は持たず、parentUserId / childUserId は値参照のみ。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ParentalConsentService {

    private final ParentalConsentLinkRepository parentalConsentLinkRepository;
    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;
    private final StringRedisTemplate redisTemplate;
    private final EmailOutboxService emailOutboxService;

    /** 招待メールのレートリミット: 24時間で最大10回 */
    private static final int INVITE_MAX_ATTEMPTS = 10;
    private static final Duration INVITE_RATE_LIMIT_WINDOW = Duration.ofHours(24);
    /** 招待トークン有効期間: 7日間 */
    private static final int TOKEN_EXPIRY_DAYS = 7;
    /** PENDING 招待の同時上限 */
    private static final int MAX_PENDING_INVITATIONS = 3;

    // ========================================
    // 子ユーザー側 — 招待操作
    // ========================================

    /**
     * 保護者を招待する。
     * レートリミット → 自己招待チェック → PENDING 上限チェック → 重複チェック →
     * トークン生成 → DB 保存 → メール送信。
     *
     * @param childUserId 子ユーザーの ID
     * @param parentEmail 保護者のメールアドレス
     */
    @Transactional
    public void inviteParent(Long childUserId, String parentEmail) {
        // 1. Valkey レートリミット
        String rateLimitKey = "mannschaft:auth:parental_consent_invite:" + childUserId;
        authTokenService.checkRateLimit(rateLimitKey, INVITE_MAX_ATTEMPTS, INVITE_RATE_LIMIT_WINDOW);

        // 2. 自己招待チェック
        UserEntity childUser = userRepository.findById(childUserId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));
        if (parentEmail.equalsIgnoreCase(childUser.getEmail())) {
            throw new BusinessException(AuthErrorCode.AUTH_069);
        }

        // 3. PENDING 上限チェック
        long pendingCount = parentalConsentLinkRepository.countByChildUserIdAndStatus(
                childUserId, ParentalConsentLinkStatus.PENDING);
        if (pendingCount >= MAX_PENDING_INVITATIONS) {
            throw new BusinessException(AuthErrorCode.AUTH_067);
        }

        // 4. 重複招待チェック
        if (parentalConsentLinkRepository.existsByChildUserIdAndParentEmailAndStatus(
                childUserId, parentEmail, ParentalConsentLinkStatus.PENDING)) {
            throw new BusinessException(AuthErrorCode.AUTH_068);
        }

        // 5. 保護者ユーザー検索（システム登録済みの場合のみ parentUserId をセット）
        Long parentUserId = userRepository.findByEmail(parentEmail)
                .map(UserEntity::getId)
                .orElse(null);

        // 6. トークン生成
        String rawToken = SecureTokenGenerator.generate();
        String tokenHash = authTokenService.hashToken(rawToken);

        // 7. ParentalConsentLinkEntity 保存
        ParentalConsentLinkEntity link = ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentUserId(parentUserId)
                .parentEmail(parentEmail)
                .tokenHash(tokenHash)
                .status(ParentalConsentLinkStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusDays(TOKEN_EXPIRY_DAYS))
                .build();
        parentalConsentLinkRepository.save(link);

        // 8. 招待メール送信
        String childDisplayName = childUser.getDisplayName() != null
                ? childUser.getDisplayName()
                : childUser.getLastName() + " " + childUser.getFirstName();
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "PARENTAL_CONSENT_INVITATION",
                "ja",
                parentEmail,
                Map.of(
                        "child_name", childDisplayName,
                        "token", rawToken,
                        "expires_days", String.valueOf(TOKEN_EXPIRY_DAYS)
                ),
                "auth",
                null,
                null,
                parentUserId,
                null
        ));

        log.info("保護者招待メール送信: childUserId={}, parentEmail={}", childUserId, parentEmail);
    }

    /**
     * 子ユーザーに紐付くすべての保護者招待を取得する。
     *
     * @param childUserId 子ユーザーの ID
     * @return 招待リンクのリスト
     */
    public List<ParentalConsentLinkEntity> getInvitations(Long childUserId) {
        return parentalConsentLinkRepository.findByChildUserId(childUserId);
    }

    /**
     * 保護者招待を取り消す（子ユーザー操作）。
     * 対象リンクが PENDING 状態の場合のみ取り消し可能。
     *
     * @param linkId      招待リンク ID（UUID 文字列）
     * @param childUserId 操作者の子ユーザー ID
     */
    @Transactional
    public void revokeInvitation(String linkId, Long childUserId) {
        ParentalConsentLinkEntity link = parentalConsentLinkRepository
                .findById(UUID.fromString(linkId))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));

        // IDOR チェック
        if (!childUserId.equals(link.getChildUserId())) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }
        // PENDING 以外は取り消し不可
        if (link.getStatus() != ParentalConsentLinkStatus.PENDING) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }

        link.revoke(childUserId);
        parentalConsentLinkRepository.save(link);
    }

    /**
     * 承認済み保護者リンクを取得する。
     *
     * @param childUserId 子ユーザーの ID
     * @return 承認済みリンクのリスト
     */
    public List<ParentalConsentLinkEntity> getApprovedParents(Long childUserId) {
        return parentalConsentLinkRepository.findByChildUserIdAndStatus(
                childUserId, ParentalConsentLinkStatus.APPROVED);
    }

    /**
     * 承認済み保護者リンクを子ユーザー側から削除する。
     * 唯一の保護者リンクを削除しようとした場合は AUTH_064 をスローする。
     *
     * @param linkId      削除対象のリンク ID
     * @param childUserId 操作者の子ユーザー ID
     */
    @Transactional
    public void removeParentalLink(String linkId, Long childUserId) {
        ParentalConsentLinkEntity link = parentalConsentLinkRepository
                .findById(UUID.fromString(linkId))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));

        // IDOR チェック
        if (!childUserId.equals(link.getChildUserId())) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }
        // APPROVED 以外は削除不可
        if (link.getStatus() != ParentalConsentLinkStatus.APPROVED) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }
        // 最後の保護者チェック
        long approvedCount = parentalConsentLinkRepository.countByChildUserIdAndStatus(
                childUserId, ParentalConsentLinkStatus.APPROVED);
        if (approvedCount <= 1) {
            throw new BusinessException(AuthErrorCode.AUTH_064);
        }

        link.revoke(childUserId);
        parentalConsentLinkRepository.save(link);
    }

    // ========================================
    // 保護者側 — 承認・拒否操作
    // ========================================

    /**
     * トークンで保護者同意リンクを検索する。
     * 存在しない・PENDING 以外・期限切れの場合は AUTH_060 をスローする。
     *
     * @param token 平文トークン
     * @return 対象の保護者同意リンク
     */
    public ParentalConsentLinkEntity getApprovalRequest(String token) {
        String tokenHash = authTokenService.hashToken(token);
        ParentalConsentLinkEntity link = parentalConsentLinkRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_060));

        if (link.getStatus() != ParentalConsentLinkStatus.PENDING) {
            throw new BusinessException(AuthErrorCode.AUTH_060);
        }
        if (link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_060);
        }
        return link;
    }

    /**
     * 保護者同意を承認する。
     * 自己承認防止 / 未成年保護者防止チェックを行い、子ユーザーを ACTIVE に遷移させる。
     *
     * @param token        平文トークン
     * @param parentUserId 承認する保護者のユーザー ID
     */
    @Transactional
    public void approveParentalConsent(String token, Long parentUserId) {
        ParentalConsentLinkEntity link = getApprovalRequest(token);

        // 自己承認チェック
        if (link.getChildUserId().equals(parentUserId)) {
            throw new BusinessException(AuthErrorCode.AUTH_062);
        }

        // 未成年保護者チェック
        UserEntity parentUser = userRepository.findById(parentUserId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));
        if (parentUser.getBirthDate() != null) {
            LocalDate parentBirthDate = LocalDate.parse(parentUser.getBirthDate());
            if (AgeGroupCalculator.isMinor(parentBirthDate, LocalDate.now())) {
                throw new BusinessException(AuthErrorCode.AUTH_063);
            }
        }

        link.approve(parentUserId);
        parentalConsentLinkRepository.save(link);

        // 子ユーザーを ACTIVE に遷移
        UserEntity childUser = userRepository.findById(link.getChildUserId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));
        childUser.activate();
        userRepository.save(childUser);

        // 承認通知メール
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "PARENTAL_CONSENT_APPROVED",
                childUser.getLocale() != null ? childUser.getLocale() : "ja",
                childUser.getEmail(),
                Map.of("child_name", childUser.getDisplayName() != null
                        ? childUser.getDisplayName()
                        : childUser.getLastName() + " " + childUser.getFirstName()),
                "auth",
                null,
                null,
                childUser.getId(),
                null
        ));

        log.info("保護者同意承認: childUserId={}, parentUserId={}", link.getChildUserId(), parentUserId);
    }

    /**
     * 保護者同意を拒否する。
     * PENDING かつ有効期限内のリンクに対してのみ実行可能。
     * 全保護者が拒否した（APPROVED / PENDING が 0 件）場合は子アカウントを論理削除する。
     *
     * @param token 平文トークン
     */
    @Transactional
    public void rejectParentalConsent(String token) {
        ParentalConsentLinkEntity link = getApprovalRequest(token);

        link.reject();
        parentalConsentLinkRepository.save(link);

        Long childUserId = link.getChildUserId();

        // 子ユーザーのリンクをすべて取得し、PENDING / APPROVED が 0 件なら子アカウントを論理削除
        List<ParentalConsentLinkEntity> allLinks = parentalConsentLinkRepository
                .findByChildUserId(childUserId);
        boolean hasPending = allLinks.stream()
                .anyMatch(l -> l.getStatus() == ParentalConsentLinkStatus.PENDING);
        boolean hasApproved = allLinks.stream()
                .anyMatch(l -> l.getStatus() == ParentalConsentLinkStatus.APPROVED);

        if (!hasPending && !hasApproved) {
            // 全保護者に拒否された場合、子アカウントを論理削除
            UserEntity childUser = userRepository.findById(childUserId)
                    .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));
            childUser.requestDeletion();
            userRepository.save(childUser);
            log.info("保護者同意全拒否 → 子アカウント論理削除: childUserId={}", childUserId);
        }

        // 拒否通知メール（子ユーザーへ）
        userRepository.findById(childUserId).ifPresent(childUser -> {
            emailOutboxService.enqueue(new EmailOutboxRequest(
                    "PARENTAL_CONSENT_REJECTED",
                    childUser.getLocale() != null ? childUser.getLocale() : "ja",
                    childUser.getEmail(),
                    Map.of(),
                    "auth",
                    null,
                    null,
                    childUser.getId(),
                    null
            ));
        });
    }

    /**
     * 保護者側からリンクを解除する。
     * 解除後に子の APPROVED リンクが 0 件になる場合は AUTH_065 をスローする。
     *
     * @param linkId       解除対象のリンク ID
     * @param parentUserId 操作者の保護者ユーザー ID
     */
    @Transactional
    public void removeParentalLinkAsParent(String linkId, Long parentUserId) {
        ParentalConsentLinkEntity link = parentalConsentLinkRepository
                .findById(UUID.fromString(linkId))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));

        // IDOR チェック
        if (!parentUserId.equals(link.getParentUserId())) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }
        // APPROVED 以外は解除不可
        if (link.getStatus() != ParentalConsentLinkStatus.APPROVED) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }
        // 子の唯一の保護者であれば解除不可
        long approvedCount = parentalConsentLinkRepository.countByChildUserIdAndStatus(
                link.getChildUserId(), ParentalConsentLinkStatus.APPROVED);
        if (approvedCount <= 1) {
            throw new BusinessException(AuthErrorCode.AUTH_065);
        }

        link.revoke(parentUserId);
        parentalConsentLinkRepository.save(link);
    }

    /**
     * 保護者として登録されている子ユーザーのリンク一覧を取得する。
     *
     * @param parentUserId 保護者のユーザー ID
     * @return 承認済みリンクのリスト
     */
    public List<ParentalConsentLinkEntity> getChildrenAsParent(Long parentUserId) {
        return parentalConsentLinkRepository.findByParentUserIdAndStatus(
                parentUserId, ParentalConsentLinkStatus.APPROVED);
    }

    /**
     * 指定ユーザーが「承認済み保護者」として登録されている子ユーザーのユーザーID一覧を返す。
     *
     * <p>F08.9 P3a 切替可能な子の列挙から呼び出される境界メソッド。
     * 集約サービスは Entity ではなく ID リストのみを受け取り、子の属性（生年月日・国コード）は
     * 別途 UserService 経由で解決する（ドメイン境界遵守）。</p>
     *
     * @param parentUserId 保護者（払い手）のユーザーID
     * @return APPROVED な保護者リンクを持つ子ユーザーのIDリスト（重複なし・空可）
     */
    public List<Long> listApprovedChildUserIds(Long parentUserId) {
        if (parentUserId == null) {
            return List.of();
        }
        return parentalConsentLinkRepository.findByParentUserIdAndStatus(
                        parentUserId, ParentalConsentLinkStatus.APPROVED)
                .stream()
                .map(ParentalConsentLinkEntity::getChildUserId)
                .distinct()
                .toList();
    }

    /**
     * バッチ用: 全 APPROVED 保護者リンクの (保護者, 子) ペアをページングで返す。
     *
     * <p>F08.9 P3c-3 自立移行通知バッチ（進学予告）から呼び出される境界メソッド。
     * id 昇順で安定ページングし、{@code pageNumber} を進めて全 APPROVED リンクを走査する。</p>
     *
     * @param pageNumber ページ番号（0 始まり）
     * @param pageSize   1 ページあたりの件数
     * @return (保護者ユーザーID, 子ユーザーID) ペアのリスト（空可）
     */
    public List<ParentChildPair> listApprovedParentChildPairs(int pageNumber, int pageSize) {
        return parentalConsentLinkRepository.findByStatusOrderByIdAsc(
                        ParentalConsentLinkStatus.APPROVED,
                        org.springframework.data.domain.PageRequest.of(pageNumber, pageSize))
                .stream()
                .map(link -> new ParentChildPair(link.getParentUserId(), link.getChildUserId()))
                .toList();
    }

    /**
     * 保護者と子の ID ペア（自立移行通知バッチの境界 DTO）。
     *
     * @param parentUserId 保護者（払い手）のユーザーID
     * @param childUserId  子のユーザーID
     */
    public record ParentChildPair(Long parentUserId, Long childUserId) {
    }

    // ========================================
    // クロスドメイン照会（payment ドメインから利用）
    // ========================================

    /**
     * 指定ユーザーが対象の子ユーザーの「承認済み保護者」であるかを判定する。
     *
     * <p>F08.9 代理払い認可（GUARDIAN 経路）から呼び出される境界メソッド。
     * payment ドメインは auth ドメインの Entity / Repository を直接参照せず、
     * 本メソッドの boolean 結果のみを受け取る（モジュラーモノリスのドメイン境界遵守）。</p>
     *
     * <p>parental_consent_links に (child=childUserId, parent=parentUserId, status=APPROVED)
     * のリンクが 1 件でも存在すれば {@code true}。権原はキャッシュせず毎回実行時評価する
     * （リンク取消で即時に権原消失するため）。</p>
     *
     * @param parentUserId 保護者候補（払い手）のユーザー ID
     * @param childUserId  子（受益者）のユーザー ID
     * @return 承認済み保護者リンクが存在する場合 true
     */
    public boolean isApprovedGuardian(Long parentUserId, Long childUserId) {
        if (parentUserId == null || childUserId == null) {
            return false;
        }
        return parentalConsentLinkRepository.existsByChildUserIdAndParentUserIdAndStatus(
                childUserId, parentUserId, ParentalConsentLinkStatus.APPROVED);
    }

    // ========================================
    // 退会ブロックチェック
    // ========================================

    /**
     * 退会前チェック: ユーザーが唯一の保護者である PENDING_PARENTAL_CONSENT 状態の子がいれば
     * AUTH_066 をスローする。
     *
     * @param userId 退会しようとしているユーザーの ID
     */
    public void checkWithdrawalBlock(Long userId) {
        List<ParentalConsentLinkEntity> approvedLinks = parentalConsentLinkRepository
                .findByParentUserIdAndStatus(userId, ParentalConsentLinkStatus.APPROVED);

        for (ParentalConsentLinkEntity link : approvedLinks) {
            Long childUserId = link.getChildUserId();

            // 子ユーザーが PENDING_PARENTAL_CONSENT 状態か確認
            boolean childIsPendingConsent = userRepository.findById(childUserId)
                    .map(u -> u.getStatus() == UserEntity.UserStatus.PENDING_PARENTAL_CONSENT)
                    .orElse(false);

            if (!childIsPendingConsent) {
                continue;
            }

            // 当該ユーザーが唯一の保護者かどうか確認
            long approvedCount = parentalConsentLinkRepository.countByChildUserIdAndStatus(
                    childUserId, ParentalConsentLinkStatus.APPROVED);
            if (approvedCount <= 1) {
                throw new BusinessException(AuthErrorCode.AUTH_066);
            }
        }
    }
}
