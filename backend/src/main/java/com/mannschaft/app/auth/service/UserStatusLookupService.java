package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 他ドメインがユーザー Entity や Repository に依存せず、有効状態を確認するための窓口。 */
@Service
@RequiredArgsConstructor
public class UserStatusLookupService {

    private final UserRepository userRepository;

    public boolean isActive(Long userId) {
        return userRepository.existsActiveById(userId);
    }
}
