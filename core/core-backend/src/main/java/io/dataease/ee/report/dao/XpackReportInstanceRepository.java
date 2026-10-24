package io.dataease.ee.report.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface XpackReportInstanceRepository extends JpaRepository<XpackReportInstance, Long>, JpaSpecificationExecutor<XpackReportInstance> {
}
