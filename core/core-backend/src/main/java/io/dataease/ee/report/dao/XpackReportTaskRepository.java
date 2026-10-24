package io.dataease.ee.report.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface XpackReportTaskRepository extends JpaRepository<XpackReportTask, Long>, JpaSpecificationExecutor<XpackReportTask> {
}
