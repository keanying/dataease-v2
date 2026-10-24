package io.dataease.ee.threshold.dao;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "xpack_threshold_info_snapshot")
@Comment("阈值告警（编辑镜像）")
public class XpackThresholdInfoSnapshot extends ThresholdInfoBase {
}
