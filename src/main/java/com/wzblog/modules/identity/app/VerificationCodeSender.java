package com.wzblog.modules.identity.app;

/**
 * 邮箱验证码发送端口；默认日志实现，测试可替换（不接 SMTP）。
 */
public interface VerificationCodeSender {

    void sendResetCode(String email, String code);
}