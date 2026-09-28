package com.legacyai.ticket;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 從 JWT 取出舊系統需要的身分資訊。 */
final class AuthInfo {
    private AuthInfo() {}

    static boolean hasRole(Authentication auth, String role) {
        String wanted = "ROLE_" + role;
        return auth.getAuthorities().stream().anyMatch(a -> wanted.equals(a.getAuthority()));
    }

    /**
     * 這張 token 是由哪個 client 取得的（azp，已簽章、無法偽造）。
     * 使用者從舊系統前端操作時是該系統的 web client；經由 Copilot 時是 copilot-web。
     * 舊系統只記錄，不依此改變權限判斷。
     */
    static String clientId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            String azp = jwt.getToken().getClaimAsString("azp");
            return azp != null ? azp : "unknown";
        }
        return "unknown";
    }

    /** RFC 8693 的代理身分（act claim），沒有則回傳 null。 */
    static String actor(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            Object act = jwt.getToken().getClaims().get("act");
            if (act instanceof Map<?, ?> m) {
                Object sub = m.get("sub");
                if (sub == null) {
                    sub = m.get("client_id");
                }
                return sub == null ? null : String.valueOf(sub);
            }
        }
        return null;
    }
}
