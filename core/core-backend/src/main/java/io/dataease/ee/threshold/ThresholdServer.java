package io.dataease.ee.threshold;

import io.dataease.api.threshold.ThresholdApi;
import io.dataease.api.threshold.dto.*;
import io.dataease.api.threshold.vo.ThresholdGridVO;
import io.dataease.api.threshold.vo.ThresholdInstanceVO;
import io.dataease.result.PageResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 阈值告警
 */
@RestController("thresholdServer")
@RequestMapping("/threshold")
public class ThresholdServer implements ThresholdApi {

    private final ThresholdManage thresholdManage;

    public ThresholdServer(ThresholdManage thresholdManage) {
        this.thresholdManage = thresholdManage;
    }

    @Override
    public void save(ThresholdCreator creator) {
        thresholdManage.save(creator);
    }

    @Override
    public void edit(ThresholdCreator creator) {
        thresholdManage.edit(creator);
    }

    @Override
    public PageResult<ThresholdGridVO> pager(int goPage, int pageSize, ThresholdGridRequest request) {
        return thresholdManage.pager(goPage, pageSize, request);
    }

    @Override
    public ThresholdCreator formInfo(Long id, String resourceTable) {
        return thresholdManage.formInfo(id, resourceTable);
    }

    @Override
    public void switchEnable(ThresholdSwitchRequest request) {
        thresholdManage.switchEnable(request);
    }

    @Override
    public void delete(List<Long> idList, String resourceTable) {
        thresholdManage.delete(idList, resourceTable);
    }

    @Override
    public void batchReci(ThresholdBatchReciRequest request) {
        thresholdManage.batchReci(request);
    }

    @Override
    public PageResult<ThresholdInstanceVO> instancePager(int goPage, int pageSize, ThresholdInstanceRequest request) {
        return thresholdManage.instancePager(goPage, pageSize, request);
    }

    @Override
    public String preview(ThresholdPreviewRequest request) {
        return thresholdManage.preview(request);
    }

    @Override
    public boolean anyThreshold(Long chartId, String resourceTable) {
        return thresholdManage.anyThreshold(chartId, resourceTable);
    }

    @Override
    public void deleteWithChart(Long chartId, String resourceTable) {
        thresholdManage.deleteWithChart(chartId, resourceTable);
    }

    @Override
    public void thresholdSnapshotRestore(ThresholdGridRequest request) {
        thresholdManage.snapshotRestore(request == null ? null : request.getChartId());
    }
}
