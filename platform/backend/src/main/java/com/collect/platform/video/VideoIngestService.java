package com.collect.platform.video;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 视频明细入库。来源：CDP 监听页面 JSON 响应（aweme_list）。
 *
 * 采集口径对齐插件 v1.4.0：只收「挂载合集 + AI 声明」双命中的作品，
 * 用于挖掘 AI 短剧达人。判定逻辑集中在 {@link #judge(Map)}，链路两侧共用。
 * 以 aweme_id 去重；已存在的视频只刷新指标，不覆盖首次采集人与采集时间。
 */
@Service
public class VideoIngestService {

    /** 一条作品的采集判定结果。 */
    public record Judgement(boolean hasMix, boolean hasAi, String mixId, String mixName,
                            Integer aigcLabelType, boolean qualified) {
    }

    /**
     * AI 信号词：命中任意一个才可能是 AI 内容。
     * 抖音 web 接口已不下发 aigc_info（实测恒为 null），只能按文案判断。
     */
    private static final String[] AI_SIGNAL_KEYWORDS = {
            "ai短剧", "ai漫剧", "ai微短剧", "ai动漫", "ai动画", "ai电影", "ai剧",
            "ai生成", "ai创作", "aigc", "ai自制"
    };

    /** 排除词：解说来路和 AI 图片、AI 舞蹈这类非短剧内容不收。 */
    private static final String[] EXCLUDE_KEYWORDS = {
            "解说", "讲剧", "拉片", "沙雕", "吐槽", "混剪", "搬运", "reaction",
            "预告", "花絮", "ai图片", "ai舞蹈"
    };

    private final JdbcTemplate jdbc;

    public VideoIngestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** v1.4.0 口径：合集 + AI 声明 + 有作者，三者齐备才算命中。 */
    public Judgement judge(Map<String, Object> item) {
        String mixId = null;
        String mixName = null;
        Map<String, Object> mix = map(item.get("mix_info"));
        if (mix == null) {
            mix = map(item.get("mixInfo"));
        }
        if (mix != null) {
            mixId = firstStr(mix, "mix_id", "id", "mixId");
            if (mixId == null && mix.get("ids") instanceof List<?> ids && !ids.isEmpty()) {
                mixId = str(ids.get(0));
            }
            mixName = firstStr(mix, "mix_name", "name", "title");
        }
        if (mixId == null) {
            mixId = str(item.get("mix_id"));
        }
        if (mixName == null) {
            mixName = str(item.get("mix_name"));
        }
        boolean hasMix = mixId != null || mixName != null;

        Integer aigcType = null;
        boolean hasAi = false;
        Map<String, Object> aigc = map(item.get("aigc_info"));
        if (aigc == null) {
            aigc = map(item.get("aigcInfo"));
        }
        if (aigc != null) {
            Object t = aigc.get("aigc_label_type");
            if (t == null) {
                t = aigc.get("aigcLabelType");
            }
            if (t instanceof Number n) {
                aigcType = n.intValue();
            } else if (t != null) {
                try {
                    aigcType = Integer.parseInt(String.valueOf(t).trim());
                } catch (NumberFormatException ignore) {
                    /* 非数字标签忽略 */
                }
            }
            if (aigcType != null && aigcType == 1) {
                hasAi = true;
            }
        }
        if (!hasAi) {
            Object createdByAi = item.get("created_by_ai");
            if (createdByAi == null) {
                createdByAi = item.get("createdByAi");
            }
            hasAi = Boolean.TRUE.equals(createdByAi)
                    || "true".equalsIgnoreCase(String.valueOf(createdByAi))
                    || "1".equals(String.valueOf(createdByAi));
        }
        String desc = str(item.get("desc"));
        if (!hasAi && desc != null && desc.contains("内容由AI生成")) {
            hasAi = true;
        }

        Map<String, Object> author = map(item.get("author"));
        String secUid = author == null ? null : str(author.get("sec_uid"));
        String all = allText(desc, mixName,
                author == null ? null : str(author.get("nickname")),
                author == null ? null : str(author.get("signature")));
        boolean aiSignal = containsAny(all, AI_SIGNAL_KEYWORDS);
        boolean excluded = containsAny(all, EXCLUDE_KEYWORDS);
        hasAi = hasAi || aiSignal;
        // 采集口径：只要 AI 生成内容就收，不再要求短剧特征或合集；
        // 解说、AI 图片、AI 舞蹈这类排除掉，其余交给上传前人工和去重环节。
        boolean qualified = aiSignal && !excluded && secUid != null;
        return new Judgement(hasMix, hasAi, trim(mixId, 64), trim(mixName, 255), aigcType, qualified);
    }

    private static String allText(String... texts) {
        StringBuilder sb = new StringBuilder();
        for (String t : texts) {
            if (t != null && !t.isEmpty()) {
                sb.append(t.toLowerCase(java.util.Locale.ROOT)).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean containsAny(String all, String[] keywords) {
        if (all == null || all.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (all.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    public int save(List<Map<String, Object>> items, String collector, String sourceUrl) {
        return save(items, collector, sourceUrl, null);
    }

    public int save(List<Map<String, Object>> items, String collector, String sourceUrl, String terminalCode) {
        return save(items, collector, sourceUrl, terminalCode, null);
    }

    public int save(List<Map<String, Object>> items, String collector, String sourceUrl, String terminalCode,
                    String searchKeyword) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (Map<String, Object> item : items) {
            Judgement j = judge(item);
            if (!j.qualified()) {
                continue;
            }
            String awemeId = str(item.get("aweme_id"));
            if (awemeId == null) {
                continue;
            }
            String title = str(item.get("desc"));
            String cover = cover(item);
            Map<String, Object> author = map(item.get("author"));
            String secUid = author == null ? null : str(author.get("sec_uid"));
            String nickname = author == null ? null : str(author.get("nickname"));
            Map<String, Object> stats = map(item.get("statistics"));
            long digg = num(stats, "digg_count");
            long comment = num(stats, "comment_count");
            long share = num(stats, "share_count");
            long collect = num(stats, "collect_count");
            String url = "https://www.douyin.com/video/" + awemeId;

            int rows = jdbc.update("""
                    INSERT IGNORE INTO videos
                      (aweme_id, url, title, cover_url, author_sec_uid, author_nickname,
                       mix_id, mix_name, has_mix, has_ai, aigc_label_type, aweme_create_time, aweme_type,
                       digg_count, comment_count, share_count, collect_count,
                       status, collected_by, terminal_code, search_keyword, collected_at, source_url)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'pending', ?, ?, ?, NOW(3), ?)
                    """, awemeId, url, trim(title, 512), trim(cover, 1024), secUid,
                    trim(nickname, 128), j.mixId(), j.mixName(), 1, 1, j.aigcLabelType(),
                    sqlTime(item.get("create_time")), intOrNull(item.get("aweme_type")),
                    digg, comment, share, collect, collector, terminalCode,
                    trim(searchKeyword, 128), trim(sourceUrl, 512));
            if (rows > 0) {
                inserted++;
                upsertAuthor(author, secUid, collector, terminalCode);
            } else {
                jdbc.update("""
                        UPDATE videos SET digg_count = ?, comment_count = ?, share_count = ?,
                               collect_count = ?, title = COALESCE(?, title),
                               mix_id = COALESCE(?, mix_id), mix_name = COALESCE(?, mix_name),
                               has_mix = 1, has_ai = 1
                        WHERE aweme_id = ?
                        """, digg, comment, share, collect, trim(title, 512),
                        j.mixId(), j.mixName(), awemeId);
            }
        }
        return inserted;
    }

    /** 达人档案：首见归因只写一次，后续只刷新资料与作品数。 */
    private void upsertAuthor(Map<String, Object> author, String secUid, String collector, String terminalCode) {
        if (author == null || secUid == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO authors
                  (sec_uid, has_mix, has_ai, first_collected_at, last_collected_at,
                   nickname, unique_id, avatar_url, signature,
                   first_seen_collector, first_seen_terminal, video_count, mix_video_count, last_video_at)
                VALUES (?,1,1,NOW(3),NOW(3),?,?,?,?,?,?,1,1,NOW(3))
                ON DUPLICATE KEY UPDATE
                  has_mix = 1, has_ai = 1,
                  last_collected_at = VALUES(last_collected_at),
                  nickname = COALESCE(VALUES(nickname), nickname),
                  unique_id = COALESCE(VALUES(unique_id), unique_id),
                  avatar_url = COALESCE(VALUES(avatar_url), avatar_url),
                  signature = COALESCE(VALUES(signature), signature),
                  video_count = video_count + 1,
                  mix_video_count = mix_video_count + 1,
                  last_video_at = VALUES(last_video_at)
                """,
                secUid, trim(str(author.get("nickname")), 128), trim(str(author.get("unique_id")), 128),
                trim(avatar(author), 1024), trim(str(author.get("signature")), 512),
                trim(collector, 32), trim(terminalCode, 64));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static String avatar(Map<String, Object> author) {
        Map<String, Object> thumb = map(author.get("avatar_thumb"));
        if (thumb == null) {
            thumb = map(author.get("avatarThumb"));
        }
        if (thumb == null) {
            thumb = map(author.get("avatar"));
        }
        if (thumb == null) {
            return null;
        }
        Object list = thumb.get("url_list");
        if (list instanceof List<?> urls && !urls.isEmpty()) {
            return String.valueOf(urls.get(0));
        }
        return null;
    }

    private static String cover(Map<String, Object> item) {
        Map<String, Object> video = map(item.get("video"));
        if (video == null) {
            return null;
        }
        Object cover = video.get("cover");
        if (!(cover instanceof Map)) {
            return null;
        }
        Object list = ((Map<?, ?>) cover).get("url_list");
        if (list instanceof List<?> urls && !urls.isEmpty()) {
            return String.valueOf(urls.get(0));
        }
        return null;
    }

    private static long num(Map<String, Object> m, String key) {
        if (m == null) {
            return 0;
        }
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 抖音 create_time 是秒级时间戳，转成 DATETIME 用；异常值按空处理。 */
    private static Object sqlTime(Object v) {
        long seconds;
        if (v instanceof Number n) {
            seconds = n.longValue();
        } else if (v != null) {
            try {
                seconds = Long.parseLong(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (seconds < 1000000000L || seconds > 4000000000L) {
            return null;
        }
        return java.time.LocalDateTime.ofEpochSecond(seconds, 0, java.time.ZoneOffset.ofHours(8));
    }

    private static String firstStr(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            String s = str(m.get(k));
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
