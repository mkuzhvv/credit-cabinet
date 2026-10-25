package com.creditcabinet.identity.persistence;

import java.util.Optional;
import java.util.UUID;

import com.creditcabinet.identity.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @Query("select u from AppUser u join fetch u.company where u.id = :id")
    Optional<AppUser> findWithCompanyById(@Param("id") UUID id);
}
