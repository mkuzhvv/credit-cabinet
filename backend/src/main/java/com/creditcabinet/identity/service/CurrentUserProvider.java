package com.creditcabinet.identity.service;

import com.creditcabinet.identity.model.CurrentUser;

public interface CurrentUserProvider {

    CurrentUser getCurrentUser();
}
