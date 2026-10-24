package io.dataease.ee.dataperm.dao.repo;

import io.dataease.ee.dataperm.dao.entity.SysVariable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SysVariableRepository extends JpaRepository<SysVariable, Long>, JpaSpecificationExecutor<SysVariable> {
}
