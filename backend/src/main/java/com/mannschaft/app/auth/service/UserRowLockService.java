package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** auth境界内でuser行の排他lockだけを提供する狭い窓口。entityは外部へ返さない。 */
@Service
@RequiredArgsConstructor
public class UserRowLockService {

    private final UserRepository userRepository;

    public enum UserState { ACTIVE, INELIGIBLE_EXISTING, ABSENT }

    @Transactional
    public UserState lock(Long userId) {
        if (userRepository.findByIdForUpdate(userId).isPresent()) {
            return UserState.ACTIVE;
        }
        return userRepository.findByIdForUpdateIncludingDeleted(userId).isPresent()
                ? UserState.INELIGIBLE_EXISTING : UserState.ABSENT;
    }

    /** 複数userをID昇順・重複排除でlockし、各userの状態を返す。 */
    @Transactional
    public Map<Long, UserState> lockAll(Long... userIds) {
        Map<Long, UserState> states = new LinkedHashMap<>();
        Arrays.stream(userIds).filter(java.util.Objects::nonNull).distinct().sorted()
                .forEach(id -> states.put(id, lock(id)));
        return states;
    }
}
