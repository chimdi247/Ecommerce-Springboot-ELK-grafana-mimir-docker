package com.phegondev.Phegon.Eccormerce.service.impl;

import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import com.phegondev.Phegon.Eccormerce.exception.EmailSendException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Renders a Thymeleaf template and sends it as multipart (HTML + plain-text) mail over SMTP. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final AppProperties properties;

    public String render(String template, Map<String, Object> model) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariables(model);
        return templateEngine.process(template, context);
    }

    /** @throws EmailSendException when rendering or SMTP delivery fails */
    public void sendHtml(String to, String subject, String template, Map<String, Object> model, String plainText) {
        try {
            String html = render(template, model);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getMail().getFrom(), properties.getMail().getBrandName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainText, html);   // plain-text alternative first, HTML second
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            throw new EmailSendException("Could not send e-mail to " + to + ": " + e.getMessage(), e);
        }
    }
}
