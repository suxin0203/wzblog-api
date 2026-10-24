package com.wzblog.modules.identity.app;

import com.wzblog.common.util.Times;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.modules.identity.domain.LoginAudit;
import com.wzblog.modules.identity.domain.LoginFailureReason;
import com.wzblog.modules.identity.infra.mapper.LoginAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录审计：失败审计用独立事务，避免被主流程回滚；任何异常只记日志。
 */
@Component
public class LoginAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(LoginAuditWriter.class);

    private final LoginAuditMapper loginAuditMapper;

    public LoginAuditWriter(LoginAuditMapper loginAuditMapper) {
        this.loginAuditMapper = loginAuditMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long userId, String usernameAttempt, LoginFailureReason reason, ClientInfo client) {
        try {
            loginAuditMapper.insert(build(userId, usernameAttempt, false, reason, client));
        } catch (RuntimeException ex) {
            log.warn("Failed to write login audit, reason={}", reason, ex);
        }
    }

    /** 成功审计与登录主事务同事务。 */
    public void recordSuccess(Long userId, String usernameAttempt, ClientInfo client) {
        loginAuditMapper.insert(build(userId, usernameAttempt, true, null, client));
    }

    private LoginAudit build(Long userId, String usernameAttempt, boolean success,
                             LoginFailureReason reason, ClientInfo client) {
        LoginAudit audit = new LoginAudit();
        audit.setUserId(userId);
        audit.setUsernameAttempt(usernameAttempt);
        audit.setSuccess(success);
        audit.setFailureReason(reason);
        audit.setIp(client == null ? null : client.ip());
        audit.setUserAgent(client == null ? null : client.userAgent());
        audit.setCreatedAt(Times.nowUtc());
        return audit;
    }
}