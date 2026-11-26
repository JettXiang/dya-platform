package com.collect.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 首次启动创建管理员：只在密码非空且账号不存在时执行 */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AuthRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final String code;
    private final String name;
    private final String password;

    public BootstrapRunner(AuthRepository repo,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.bootstrap.code:}") String code,
                           @Value("${app.bootstrap.name:}") String name,
                           @Value("${app.bootstrap.password:}") String password) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.code = code;
        this.name = name;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (code == null || code.isBlank() || password == null || password.isBlank()) {
            log.warn("BOOTSTRAP_SKIPPED 未提供 BOOTSTRAP_CODE/BOOTSTRAP_PASSWORD");
            return;
        }
        if (repo.existsByCode(code)) {
            return;
        }
        repo.insertAdmin(code, name, passwordEncoder.encode(password));
        log.info("BOOTSTRAP_USER_CREATED code={}", code);
    }
}
