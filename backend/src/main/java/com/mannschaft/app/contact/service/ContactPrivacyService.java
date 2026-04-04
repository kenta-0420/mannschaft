package com.mannschaft.app.contact.service;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.ContactErrorCode;
import com.mannschaft.app.contact.OnlineVisibility;
import com.mannschaft.app.contact.dto.ContactPrivacyRequest;
import com.mannschaft.app.contact.dto.ContactPrivacyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 連絡先プライバシー設定サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContactPrivacyService {

    private final UserRepository userRepository;

    /**
     * プライバシー設定を取得する。
     */
    public ContactPrivacyResponse getPrivacySettings(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));
        return toResponse(user);
    }

    /**
     * プライバシー設定を更新する。
     */
    @Transactional
    public ContactPrivacyResponse updatePrivacySettings(Long userId, ContactPrivacyRequest req) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));

        DmReceiveFrom dmReceiveFrom = req.getDmReceiveFrom() != null
                ? DmReceiveFrom.valueOf(req.getDmReceiveFrom()) : null;
        OnlineVisibility onlineVisibility = req.getOnlineVisibility() != null
                ? OnlineVisibility.valueOf(req.getOnlineVisibility()) : null;

        user.updateContactPrivacy(
                req.getHandleSearchable(),
                req.getContactApprovalRequired(),
                dmReceiveFrom,
                onlineVisibility
        );

        return toResponse(user);
    }

    private ContactPrivacyResponse toResponse(UserEntity user) {
        return ContactPrivacyResponse.builder()
                .handleSearchable(user.getHandleSearchable())
                .contactApprovalRequired(user.getContactApprovalRequired())
                .dmReceiveFrom(user.getDmReceiveFrom().name())
                .onlineVisibility(user.getOnlineVisibility().name())
                .build();
    }
}
