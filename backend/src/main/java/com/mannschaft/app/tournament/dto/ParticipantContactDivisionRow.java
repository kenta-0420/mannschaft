package com.mannschaft.app.tournament.dto;

/**
 * 参加者向け連絡スペース一覧の参加判定クエリの射影（CMP-261118-0900 §10.3.4 手順3）。
 */
public interface ParticipantContactDivisionRow {

    Long getDivisionId();

    String getDivisionName();

    String getTournamentName();
}
