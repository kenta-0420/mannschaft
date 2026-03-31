package com.mannschaft.app.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * SES v2 を使ったメール送信サービス。失敗してもログのみで例外を吸収する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private static final String FROM_ADDRESS = "noreply@mannschaft.app";

    private final SesV2Client sesV2Client;

    /**
     * HTML メールを送信する。
     *
     * @param recipient 宛先メールアドレス
     * @param subject   件名
     * @param htmlBody  HTML 本文
     */
    public void sendEmail(String recipient, String subject, String htmlBody) {
        try {
            SendEmailResponse response = sesV2Client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(FROM_ADDRESS)
                    .destination(Destination.builder().toAddresses(recipient).build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder().data(subject).build())
                                    .body(Body.builder()
                                            .html(Content.builder().data(htmlBody).build())
                                            .build())
                                    .build())
                            .build())
                    .build());
            log.info("SES送信成功: to={}, messageId={}", recipient, response.messageId());
        } catch (Exception e) {
            log.error("SES送信失敗: to={}, subject={}", recipient, subject, e);
        }
    }
}
