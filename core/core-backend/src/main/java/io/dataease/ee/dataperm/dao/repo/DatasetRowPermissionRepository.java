package io.dataease.ee.dataperm.dao.repo;

import io.dataease.ee.dataperm.dao.entity.DatasetRowPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DatasetRowPermissionRepository extends JpaRepository<DatasetRowPermission, Long>, JpaSpecificationExecutor<DatasetRowPermission> {
}
