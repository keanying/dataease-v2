package io.dataease.ee.permissions.dao.repo;

import io.dataease.ee.permissions.dao.entity.PerOrg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerOrgRepository extends JpaRepository<PerOrg, Long>, JpaSpecificationExecutor<PerOrg> {
}
