package com.mannschaft.app.membership.service;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import com.mannschaft.app.membership.dto.PlatformCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 本人状態をauthの公開窓口で確認してからmembership writerへ渡す非TX段取り役。 */
@Service
@RequiredArgsConstructor
public class PlatformCardService {
    private static final String PLATFORM_CARD_UNIQUE = "uq_member_cards_active_platform_user_id";
    private final UserService userService;
    private final PlatformCardWriter platformCardWriter;

    public ApiResponse<PlatformCardResponse> ensure(Long actorUserId) {
        if (actorUserId == null || actorUserId <= 0) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        UserService.ActiveDisplayState actor = userService.getActiveDisplayStates(List.of(actorUserId))
                .get(actorUserId);
        if (actor == null || !actor.active()) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        String displayName = actor.displayName() == null || actor.displayName().isBlank()
                ? "メンバー" : actor.displayName();
        try {
            return ApiResponse.of(platformCardWriter.ensure(actorUserId, displayName));
        } catch (DataIntegrityViolationException failure) {
            if (!isPlatformCardUniqueConflict(failure)) {
                throw failure;
            }
            // writerのrollback完了後、別transactionの新しいsnapshotで競合勝者を読む。
            return platformCardWriter.findExisting(actorUserId).map(ApiResponse::of)
                    .orElseThrow(() -> failure);
        }
    }
    private boolean isPlatformCardUniqueConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return PLATFORM_CARD_UNIQUE.equals(name)
                        || (name != null && name.endsWith("." + PLATFORM_CARD_UNIQUE));
            }
        }
        return false;
    }
}
