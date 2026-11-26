package com.collect.platform.search;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 搜索采集词库：
 *  - 每次搜索自动记词（hit_count / 首次、最近使用时间）
 *  - 每 5 分钟按人独立随机发一批可用词，供采集端直接点选
 *  - 每周按词汇总采集量与衍生建议，管理员可查可导出
 * 业务规则在 SearchKeywordService / KeywordAnalysisService，本类只做路由。
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchKeywordController {

    private final SearchKeywordService keywordService;
    private final KeywordAnalysisService analysisService;

    public SearchKeywordController(SearchKeywordService keywordService, KeywordAnalysisService analysisService) {
        this.keywordService = keywordService;
        this.analysisService = analysisService;
    }

    /* ================= 自动记词 ================= */

    @PostMapping("/keywords")
    public Result<Map<String, Object>> recordKeyword(@RequestBody Map<String, Object> body) {
        return Result.ok(keywordService.record(body));
    }

    /* ================= 随机词库（每 5 分钟一批，带近 7 天产出量） ================= */

    @GetMapping("/keywords/random")
    public Result<Map<String, Object>> randomKeywords(@RequestParam(required = false, defaultValue = "10") int n) {
        return Result.ok(keywordService.randomKeywords(n));
    }

    /* ================= 词库维护（管理员） ================= */

    @GetMapping("/keywords")
    public Result<List<Map<String, Object>>> listKeywords() {
        return Result.ok(keywordService.list());
    }

    @PostMapping("/keywords/manual")
    @Audited("新增采集词")
    public Result<Map<String, Object>> addKeyword(@RequestBody Map<String, Object> body) {
        return Result.ok(keywordService.add(body));
    }

    @PutMapping("/keywords/{id}")
    @Audited("修改采集词")
    public Result<Map<String, Object>> updateKeyword(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.ok(keywordService.update(id, body));
    }

    @DeleteMapping("/keywords/{id}")
    @Audited("删除采集词")
    public Result<Void> deleteKeyword(@PathVariable long id) {
        keywordService.delete(id);
        return Result.ok(null);
    }

    /* ================= 新词提交与审批 ================= */

    /** 采集员提交新搜索词，先进待审批，管理员通过后才进词库。 */
    @PostMapping("/keywords/suggest")
    public Result<Map<String, Object>> suggestKeyword(@RequestBody Map<String, Object> body) {
        return Result.ok(keywordService.suggest(body));
    }

    /** 待审批列表：管理员看全部，采集员只看自己提交的。 */
    @GetMapping("/keywords/suggestions")
    public Result<List<Map<String, Object>>> listSuggestions(@RequestParam(required = false) String status) {
        return Result.ok(keywordService.listSuggestions(status));
    }

    /** 审批：通过则自动写进词库，驳回需填原因。 */
    @PostMapping("/keywords/suggestions/{id}/review")
    @Audited("审批新搜索词")
    public Result<Map<String, Object>> reviewSuggestion(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.ok(keywordService.review(id, body));
    }

    /* ================= 每周分析 ================= */

    @GetMapping("/analysis")
    public Result<Map<String, Object>> analysis(@RequestParam(required = false) String week) {
        return Result.ok(analysisService.analysis(week));
    }

    @GetMapping("/analysis/export")
    public void export(@RequestParam(required = false) String week, HttpServletResponse response) throws IOException {
        Map<String, Object> data = analysisService.analysis(week);
        byte[] csv = analysisService.toCsv(data).getBytes(StandardCharsets.UTF_8);
        String filename = "keyword-analysis-" + data.get("week") + ".csv";
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(csv);
    }
}
