package com.mannschaft.app.errorreport.event;

/** FE の新規・再発 HIGH 以上だけが発行する、commit 後の AI 分析要求。 */
public record ErrorReportAiAnalysisRequestedEvent(Long reportId) { }
