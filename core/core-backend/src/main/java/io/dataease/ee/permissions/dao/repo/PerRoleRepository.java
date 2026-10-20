package io.dataease.ee.permissions.dao.repo;

import io.dataease.ee.permissions.dao.entity.PerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerRoleRepository extends JpaRepository<PerRole, Long>, JpaSpecificationExecutor<PerRole> {
}
