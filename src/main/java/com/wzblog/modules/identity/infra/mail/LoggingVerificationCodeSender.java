package com.wzblog.modules.identity.infra.mail;

import com.wzblog.modules.identity.app.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认发送实现：只打日志（不接 SMTP）。邮箱做掩码，验证码仅本地开发可见。
 */
@Component
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationCodeSender.class);

    @Override
    public void sendResetCode(String email, String code) {
        log.info("Password reset verification code issued for {}: {}", mask(email), code);
    }

    private String mask(String email) {
        if (email == null) {
            return "unknown";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***" + email.substring(at);
    }
}