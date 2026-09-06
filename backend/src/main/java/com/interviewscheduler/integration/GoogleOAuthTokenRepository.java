package com.interviewscheduler.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GoogleOAuthTokenRepository extends JpaRepository<GoogleOAuthToken, UUID> {

    Optional<GoogleOAuthToken> findByUserId(UUID userId);
}
