package io.dataease.ee.log.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface XpackLogRepository extends JpaRepository<XpackLog, Long>, JpaSpecificationExecutor<XpackLog> {
}
