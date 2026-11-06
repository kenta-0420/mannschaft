package com.mannschaft.app.family.service;

import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.role.api.CareLinkQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** familyのケアリンク照会を認可SPIへ提供する。nullの利用者IDは未連携として扱う。 */
@Component
@RequiredArgsConstructor
public class FamilyCareLinkQuery implements CareLinkQuery {
    private final UserCareLinkRepository userCareLinkRepository;

    @Override
    public boolean hasActiveCareLink(Long watcherUserId, Long careRecipientUserId) {
        if (watcherUserId == null || careRecipientUserId == null) {
            return false;
        }
        return userCareLinkRepository.existsByCareRecipientUserIdAndWatcherUserIdAndStatus(
                careRecipientUserId, watcherUserId, CareLinkStatus.ACTIVE);
    }
}
