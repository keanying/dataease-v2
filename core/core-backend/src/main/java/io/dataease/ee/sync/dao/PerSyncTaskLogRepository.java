package io.dataease.ee.sync.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerSyncTaskLogRepository extends JpaRepository<PerSyncTaskLog, Long>, JpaSpecificationExecutor<PerSyncTaskLog> {
}
