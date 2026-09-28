package com.legacyai.ticket;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 典型的舊系統做法：拿使用者的 SSO token 去問公司的 profile API「這個人在本系統有哪些角色」。
 * <p>
 * 角色查詢結果以（使用者, 系統）為鍵快取一小段時間，避免每個請求都打 profile API。
 * 取捨：角色被撤銷後，最多要等 TTL 才生效。
 * profile API 無法連線時回傳空角色（fail closed → 403），不會放行。
 */
@Component
public class ProfileClient {

    private static final Logger log = LoggerFactory.getLogger(ProfileClient.class);

    private record Entry(List<String> roles, Instant expiresAt) {}

    private final RestClient rest;
    private final String system;
    private final Duration ttl;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public ProfileClient(@Value("${profile.base-url:http://localhost:8084}") String baseUrl,
                         @Value("${profile.system}") String system,
                         @Value("${profile.cache-ttl:60s}") Duration ttl) {
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
        this.system = system;
        this.ttl = ttl;
    }

    public List<String> rolesOf(Jwt jwt) {
        String key = jwt.getSubject();
        Entry e = cache.get(key);
        if (e != null && e.expiresAt().isAfter(Instant.now())) {
            return e.roles();
        }
        try {
            List<String> roles = rest.get()
                .uri(u -> u.path("/api/me/roles").queryParam("system", system).build())
                .headers(h -> h.setBearerAuth(jwt.getTokenValue()))
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {});
            List<String> safe = roles == null ? List.of() : List.copyOf(roles);
            cache.put(key, new Entry(safe, Instant.now().plus(ttl)));
            return safe;
        } catch (RuntimeException ex) {
            log.warn("profile API 查詢失敗，視為沒有任何角色：{}", ex.getMessage());
            return List.of();
        }
    }
}
