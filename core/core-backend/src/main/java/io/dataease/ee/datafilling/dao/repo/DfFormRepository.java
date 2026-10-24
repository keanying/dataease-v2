package io.dataease.ee.datafilling.dao.repo;

import io.dataease.ee.datafilling.dao.entity.DfForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DfFormRepository extends JpaRepository<DfForm, Long>, JpaSpecificationExecutor<DfForm> {
}
