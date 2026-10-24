package io.dataease.ee.dataperm.dao.repo;

import io.dataease.ee.dataperm.dao.entity.SysVariableUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SysVariableUserRepository extends JpaRepository<SysVariableUser, Long>, JpaSpecificationExecutor<SysVariableUser> {
}
