package io.dataease.ee.dataperm.dao.repo;

import io.dataease.ee.dataperm.dao.entity.SysVariableValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SysVariableValueRepository extends JpaRepository<SysVariableValue, Long>, JpaSpecificationExecutor<SysVariableValue> {
}
