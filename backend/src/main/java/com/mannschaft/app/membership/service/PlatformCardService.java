package com.mannschaft.app.membership.service;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import com.mannschaft.app.membership.dto.PlatformCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 本人状態をauthの公開窓口で確認してからmembership writerへ渡す非TX段取り役。 */
@Service
@RequiredArgsConstructor
public class PlatformCardService {
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
            // writerのrollback完了後、別transactionの新しいsnapshotで競合勝者を読む。
            return platformCardWriter.findExisting(actorUserId).map(ApiResponse::of)
                    .orElseThrow(() -> failure);
        }
    }
}
