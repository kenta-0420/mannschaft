package com.mannschaft.app.membership.service;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.ApiResponse;
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
        throw new UnsupportedOperationException("共通会員証の本人状態確認は未実装");
    }
}
