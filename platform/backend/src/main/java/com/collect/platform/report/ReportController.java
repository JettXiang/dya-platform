package com.collect.platform.report;

import com.collect.platform.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/daily")
    public Result<Map<String, Object>> daily(@RequestParam String date) {
        return Result.ok(service.daily(date));
    }

    /** 采集日报 CSV：按采集人汇总当天入库量、任务完成率，可直接用 Excel 打开。 */
    @GetMapping("/collection-export")
    public void collectionExport(@RequestParam(required = false) String date,
                                 HttpServletResponse response) throws IOException {
        String csv = service.collectionCsv(date);
        String day = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date.trim();
        String filename = "collection-report-" + day + ".csv";
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(csv.getBytes(StandardCharsets.UTF_8));
    }
}
