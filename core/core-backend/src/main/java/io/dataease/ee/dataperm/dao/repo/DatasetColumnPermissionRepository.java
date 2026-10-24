package io.dataease.ee.dataperm.dao.repo;

import io.dataease.ee.dataperm.dao.entity.DatasetColumnPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DatasetColumnPermissionRepository extends JpaRepository<DatasetColumnPermission, Long>, JpaSpecificationExecutor<DatasetColumnPermission> {
}
