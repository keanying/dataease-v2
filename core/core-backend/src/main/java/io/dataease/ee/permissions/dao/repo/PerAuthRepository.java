package io.dataease.ee.permissions.dao.repo;

import io.dataease.ee.permissions.dao.entity.PerAuth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerAuthRepository extends JpaRepository<PerAuth, Long>, JpaSpecificationExecutor<PerAuth> {
}
