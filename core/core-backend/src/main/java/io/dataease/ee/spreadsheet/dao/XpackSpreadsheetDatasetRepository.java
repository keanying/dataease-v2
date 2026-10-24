package io.dataease.ee.spreadsheet.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface XpackSpreadsheetDatasetRepository extends JpaRepository<XpackSpreadsheetDataset, Long>, JpaSpecificationExecutor<XpackSpreadsheetDataset> {
}
