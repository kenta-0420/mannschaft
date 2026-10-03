package com.mannschaft.app.mail.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * F09.18: 実 AWS SES 送信トランスポート。
 *
 * <p>{@code mannschaft.email.simulate=false} または未設定（既定 false）のとき有効。
 * prod / staging では常にこちらが選択される。</p>
 *
 * <p>SES 例外はそのまま伝播させる（catch しない）。
 * 呼び出し側（EmailOutboxServiceImpl）が SesExceptionClassifier で永久/一時を判定する。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mannschaft.email.simulate", havingValue = "false", matchIfMissing = true)
public class SesEmailTransport implements EmailTransport {

    private final SesV2Client sesClient;

    @Value("${mannschaft.email.from-address:noreply@mannschaft.app}")
    private String fromAddress;

    @Override
    public String send(String toAddress, String subject, String htmlBody) {
        return sesClient.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(fromAddress)
                .destination(Destination.builder().toAddresses(toAddress).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data(subject).build())
                                .body(Body.builder()
                                        .html(Content.builder().data(htmlBody).build())
                                        .build())
                                .build())
                        .build())
                .build()).messageId();
    }
}
