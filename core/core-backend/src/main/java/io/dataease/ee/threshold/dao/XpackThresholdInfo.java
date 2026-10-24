package io.dataease.ee.threshold.dao;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "xpack_threshold_info")
@Comment("阈值告警（已发布）")
public class XpackThresholdInfo extends ThresholdInfoBase {
}
