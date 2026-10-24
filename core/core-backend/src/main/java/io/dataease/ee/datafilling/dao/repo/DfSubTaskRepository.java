package io.dataease.ee.datafilling.dao.repo;

import io.dataease.ee.datafilling.dao.entity.DfSubTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DfSubTaskRepository extends JpaRepository<DfSubTask, Long>, JpaSpecificationExecutor<DfSubTask> {
}
