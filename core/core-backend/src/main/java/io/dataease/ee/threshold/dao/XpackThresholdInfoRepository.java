package io.dataease.ee.threshold.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface XpackThresholdInfoRepository extends JpaRepository<XpackThresholdInfo, Long>, JpaSpecificationExecutor<XpackThresholdInfo> {
}
