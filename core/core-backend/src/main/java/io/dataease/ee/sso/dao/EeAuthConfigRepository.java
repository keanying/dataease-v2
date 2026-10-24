package io.dataease.ee.sso.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EeAuthConfigRepository extends JpaRepository<EeAuthConfig, Long>, JpaSpecificationExecutor<EeAuthConfig> {
}
