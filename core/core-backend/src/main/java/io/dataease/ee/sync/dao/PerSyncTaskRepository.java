package io.dataease.ee.sync.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerSyncTaskRepository extends JpaRepository<PerSyncTask, Long>, JpaSpecificationExecutor<PerSyncTask> {
}
