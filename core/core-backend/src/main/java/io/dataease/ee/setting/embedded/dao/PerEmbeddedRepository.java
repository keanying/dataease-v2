package io.dataease.ee.setting.embedded.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface PerEmbeddedRepository extends JpaRepository<PerEmbedded, Long>, JpaSpecificationExecutor<PerEmbedded> {

    Optional<PerEmbedded> findByAppId(String appId);
}
