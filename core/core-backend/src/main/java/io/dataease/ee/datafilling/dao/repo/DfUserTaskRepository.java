package io.dataease.ee.datafilling.dao.repo;

import io.dataease.ee.datafilling.dao.entity.DfUserTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DfUserTaskRepository extends JpaRepository<DfUserTask, Long>, JpaSpecificationExecutor<DfUserTask> {
}
