package io.dataease.ee.plugin.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface XpackPluginRepository extends JpaRepository<XpackPlugin, Long>, JpaSpecificationExecutor<XpackPlugin> {
}
