package com.ratelimiter.repository;

import com.ratelimiter.model.entity.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {

    Optional<ApiClient> findByApiKeyHash(String apiKeyHash);

    Optional<ApiClient> findByClientId(String clientId);
}
