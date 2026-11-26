package com.collect.platform.auth;

import com.collect.platform.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MeService {

    private final UserProfileRepository repo;

    public MeService(UserProfileRepository repo) {
        this.repo = repo;
    }

    /** 用户不存在时返回 null，由 controller 转成失败提示。 */
    public Map<String, Object> me() {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            return null;
        }
        Map<String, Object> user = repo.findUser(ctx.getUserId());
        if (user == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        out.put("accounts", repo.listAccounts(ctx.getUserId()));
        return out;
    }
}
