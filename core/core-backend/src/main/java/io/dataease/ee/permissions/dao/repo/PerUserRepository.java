package io.dataease.ee.permissions.dao.repo;

import io.dataease.ee.permissions.dao.entity.PerUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerUserRepository extends JpaRepository<PerUser, Long>, JpaSpecificationExecutor<PerUser> {
}
