package io.dataease.ee.permissions.dao.repo;

import io.dataease.ee.permissions.dao.entity.PerUserPlatform;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerUserPlatformRepository extends JpaRepository<PerUserPlatform, Long>, JpaSpecificationExecutor<PerUserPlatform> {
}
