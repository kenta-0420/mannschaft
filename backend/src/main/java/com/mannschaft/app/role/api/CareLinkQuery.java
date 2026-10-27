package com.mannschaft.app.role.api;

/** 見守り者と対象者のACTIVEなケアリンクを問い合わせるcore所有SPI。 */
public interface CareLinkQuery {
    boolean hasActiveCareLink(Long watcherUserId, Long careRecipientUserId);
}
