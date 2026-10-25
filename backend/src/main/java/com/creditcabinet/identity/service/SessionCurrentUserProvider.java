package com.creditcabinet.identity.service;

import com.creditcabinet.identity.model.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SessionCurrentUserProvider implements CurrentUserProvider {

    @Override
    public CurrentUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        throw new IllegalStateException("Операция требует пользователя текущей сессии");
    }
}
