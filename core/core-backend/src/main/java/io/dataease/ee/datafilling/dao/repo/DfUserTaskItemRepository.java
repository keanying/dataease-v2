package io.dataease.ee.datafilling.dao.repo;

import io.dataease.ee.datafilling.dao.entity.DfUserTaskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DfUserTaskItemRepository extends JpaRepository<DfUserTaskItem, Long>, JpaSpecificationExecutor<DfUserTaskItem> {
}
