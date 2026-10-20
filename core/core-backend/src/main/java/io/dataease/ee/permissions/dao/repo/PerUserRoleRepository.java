package io.dataease.ee.permissions.dao.repo;

import io.dataease.ee.permissions.dao.entity.PerUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerUserRoleRepository extends JpaRepository<PerUserRole, Long>, JpaSpecificationExecutor<PerUserRole> {
}
