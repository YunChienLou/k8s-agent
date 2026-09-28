package com.legacyai.profile;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全公司共用的權限查詢。SSO 只負責「你是誰」，「你在各系統能做什麼」由這裡決定。
 * 各系統收到請求時，拿同一張 SSO token 來問：這個人在我這個系統有哪些角色？
 */
@RestController
@RequestMapping("/api/me")
public class ProfileController {

    /** 使用者 → 系統 → 角色。demo 用固定資料。 */
    private static final Map<String, Map<String, List<String>>> ROLES = Map.of(
        "alice", Map.of("order", List.of("cs_agent"), "logistics", List.of("cs_agent"), "ticket", List.of("cs_agent")),
        "bob",   Map.of("order", List.of("cs_agent"), "logistics", List.of("cs_agent"), "ticket", List.of("cs_agent")),
        "carol", Map.of("order", List.of("cs_supervisor"), "logistics", List.of("cs_supervisor"),
                        "ticket", List.of("cs_supervisor")),
        "wang",  Map.of("logistics", List.of("logistics_staff")),
        "dave",  Map.of("ops", List.of("sre")));

    public record Profile(String username, Map<String, List<String>> systems) {}

    @GetMapping
    public Profile me(Authentication auth) {
        return new Profile(auth.getName(), ROLES.getOrDefault(auth.getName(), Map.of()));
    }

    @GetMapping("/roles")
    public List<String> roles(@RequestParam String system, Authentication auth) {
        return ROLES.getOrDefault(auth.getName(), Map.of()).getOrDefault(system, List.of());
    }
}
