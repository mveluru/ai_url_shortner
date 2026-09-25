package com.urlshortener.common.security;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {}
