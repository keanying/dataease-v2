package io.dataease.ee.permissions.dao.repo;

import io.dataease.ee.permissions.dao.entity.PerUserOrg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerUserOrgRepository extends JpaRepository<PerUserOrg, Long>, JpaSpecificationExecutor<PerUserOrg> {
}
