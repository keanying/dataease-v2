package io.dataease.ee.auth.revoke;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerTokenRevokedRepository extends JpaRepository<PerTokenRevoked, Long>, JpaSpecificationExecutor<PerTokenRevoked> {
}
