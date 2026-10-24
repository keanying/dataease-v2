package io.dataease.ee.datafilling.dao.repo;

import io.dataease.ee.datafilling.dao.entity.DfCommitRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DfCommitRecordRepository extends JpaRepository<DfCommitRecord, Long>, JpaSpecificationExecutor<DfCommitRecord> {
}
