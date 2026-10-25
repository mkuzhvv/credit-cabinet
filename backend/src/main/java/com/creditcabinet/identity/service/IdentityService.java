package com.creditcabinet.identity.service;

import java.util.List;
import java.util.UUID;

import com.creditcabinet.identity.api.CurrentUserView;
import com.creditcabinet.identity.model.AppUser;
import com.creditcabinet.identity.model.Company;
import com.creditcabinet.identity.model.CurrentUser;
import com.creditcabinet.identity.persistence.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IdentityService {

    private static final UUID DEMO_USER_ID = UUID.fromString("b827db91-df42-4d75-b5e0-8f0a7a512ee1");

    private final AppUserRepository users;
    private final CurrentUserProvider currentUsers;
    private final HttpSessionSecurityContextRepository securityContexts;
    private final HttpSessionCsrfTokenRepository csrfTokens;

    public IdentityService(AppUserRepository users, CurrentUserProvider currentUsers,
            HttpSessionSecurityContextRepository securityContexts, HttpSessionCsrfTokenRepository csrfTokens) {
        this.users = users;
        this.currentUsers = currentUsers;
        this.securityContexts = securityContexts;
        this.csrfTokens = csrfTokens;
    }

    public CurrentUserView getCurrentUser() {
        return loadUser(currentUsers.getCurrentUser().userId());
    }

    public CurrentUserView initializeDemoSession(HttpServletRequest request, HttpServletResponse response) {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated() && !(existing instanceof AnonymousAuthenticationToken)) {
            return getCurrentUser();
        }

        CurrentUserView user = loadUser(DEMO_USER_ID);
        CurrentUser principal = new CurrentUser(user.user().id(), user.company().id());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());

        request.getSession();
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        securityContexts.saveContext(context, request, response);
        csrfTokens.saveToken(null, request, response);
        return user;
    }

    private CurrentUserView loadUser(UUID id) {
        AppUser user = users.findWithCompanyById(id)
                .orElseThrow(() -> new IllegalStateException("Подготовленный пользователь отсутствует в БД"));
        Company company = user.getCompany();
        return new CurrentUserView(
                new CurrentUserView.UserView(user.getId(), user.getDisplayName()),
                new CurrentUserView.CompanyView(company.getId(), company.getName(), company.getLegalForm(),
                        company.getRegistrationDate(), company.getCountryCode(), company.hasSettlementAccount(),
                        company.hasRegularInflowLast3Months()),
                true);
    }
}
