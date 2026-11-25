# -*- coding: utf-8 -*-
"""
upload_tool_v2 的本地 mock 自测（不连真 Redis）
=====================================================================
用内存版假 Redis 跑核心写库逻辑，覆盖：
  - 解析两种导出 CSV（content.js / popup.js 的列结构）
  - 解析旧式 txt（每行一个 sec_uid）
  - 去重 + 元数据合并
  - 重复上传：本次新增归零、集合总数不变
  - 元数据跨次合并：first_seen 取最早、last_seen 取最晚、video_count 取最大
  - sec_uid 归一化（URL 包裹 / 引号 / 行尾逗号）
用法：py test_upload_tool.py
"""

import os
import sys
import json
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upload_tool_v2 as T


# ===================== 假 Redis =====================
class FakeRedis:
    def __init__(self):
        self.sets = {}
        self.hashes = {}

    def ping(self):
        return True

    def sadd(self, key, *values):
        s = self.sets.setdefault(key, set())
        n = 0
        for v in values:
            if v not in s:
                s.add(v)
                n += 1
        return n

    def scard(self, key):
        return len(self.sets.get(key, ()))

    def sismember(self, key, value):
        return value in self.sets.get(key, set())

    def hset(self, name, key, value):
        self.hashes.setdefault(name, {})[key] = value
        return 1

    def hget(self, name, key):
        return self.hashes.get(name, {}).get(key)

    def hgetall(self, name):
        return dict(self.hashes.get(name, {}))


CFG = {"set_key": "the_set", "meta_key": "the_meta"}

PASS = 0
FAIL = 0


def check(label, cond, extra=None):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [PASS] " + label)
    else:
        FAIL += 1
        print("  [FAIL] " + label + ("" if extra is None else "   -> " + json.dumps(extra, ensure_ascii=False)))


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


def main():
    tmp = tempfile.mkdtemp(prefix="uptool_")

    # ---------- 1. content.js 风格 CSV ----------
    print("\n[1] content.js 风格 CSV（含引号内逗号 / 重复行 / 空 uid 行）")
    p1 = write(os.path.join(tmp, "a.csv"),
               "昵称,sec_uid,合集名,搜索词,首次发现时间,最近发现时间,视频数\r\n"
               '小明,MS4wLjABAAAAaa01,"漫剧,合集",AI漫剧 逆袭,2026-09-01 10:00:00,2026-09-10 10:00:00,3\r\n'
               "小红,MS4wLjABAAAAaa02,短剧剧场,AI短剧 重生,2026-09-02 10:00:00,2026-09-11 10:00:00,1\r\n"
               "小明,MS4wLjABAAAAaa01,漫剧,AI漫剧 逆袭,2026-09-01 10:00:00,2026-09-12 10:00:00,5\r\n"
               ",MS4wLjABAAAAaa03,,,,,\r\n"
               ",,,,,\r\n")
    recs = T.load_records(p1)
    check("去重后 3 位（aa01/aa02/aa03）", len(recs) == 3, len(recs))
    aa01 = [r for r in recs if r["sec_uid"] == "MS4wLjABAAAAaa01"][0]
    check("aa01 视频数取大 = 5", aa01["video_count"] == 5, aa01["video_count"])
    check("aa01 最近发现取晚 = 09-12", aa01["last_seen"] == "2026-09-12 10:00:00", aa01["last_seen"])
    check("aa01 合集名带逗号未被拆列", aa01["mix_name"] == "漫剧,合集", aa01["mix_name"])

    r1 = FakeRedis()
    res = T.upload_records(recs, CFG, client=r1)
    check("首次上传 文件有效=3", res["valid"] == 3, res)
    check("首次上传 本次新增=3", res["added"] == 3, res)
    check("首次上传 集合总数=3", res["total"] == 3, res)
    check("元数据写入=2（aa03 无元数据不写）", res["meta_written"] == 2, res)

    # ---------- 2. popup.js 风格 CSV ----------
    print("\n[2] popup.js 风格 CSV（列更多、整列带引号）")
    p2 = write(os.path.join(tmp, "b.csv"),
               "昵称,sec_uid,抖音号,uid,合集名,合集ID,示例链接,视频数\r\n"
               '甲,"MS4wLjABAAAApp01","dy01","123","AIGC动画","mix1","https://x","4"\r\n'
               "乙,MS4wLjABAAAApp02,dy02,124,动画,mix2,https://y,2\r\n")
    recs2 = T.load_records(p2)
    check("解析出 2 位", len(recs2) == 2, len(recs2))
    r2 = FakeRedis()
    res2 = T.upload_records(recs2, CFG, client=r2)
    check("本次新增=2", res2["added"] == 2, res2)
    check("元数据写入=2", res2["meta_written"] == 2, res2)

    # ---------- 3. 旧式 txt ----------
    print("\n[3] 旧式 txt（每行一个 sec_uid，含空格与重复）")
    p3 = write(os.path.join(tmp, "c.txt"),
               "MS4wLjABAAAAt01\n"
               "  MS4wLjABAAAAt01  \n"
               "MS4wLjABAAAAt02\n"
               "\n"
               "   \n")
    recs3 = T.load_records(p3)
    check("去重后 2 位", len(recs3) == 2, len(recs3))
    r3 = FakeRedis()
    res3 = T.upload_records(recs3, CFG, client=r3)
    check("txt 本次新增=2", res3["added"] == 2, res3)
    check("txt 不写元数据=0", res3["meta_written"] == 0, res3)

    # ---------- 4. 重复上传（幂等） ----------
    print("\n[4] 同一文件再传一次：新增应为 0，总数不变")
    res4 = T.upload_records(T.load_records(p1), CFG, client=r1)
    check("文件有效仍=3", res4["valid"] == 3, res4)
    check("本次新增=0", res4["added"] == 0, res4)
    check("集合总数仍=3", res4["total"] == 3, res4)

    # ---------- 5. 元数据跨次合并 ----------
    print("\n[5] 元数据跨次合并：旧昵称保留 / first_seen 取最早 / last_seen 取最晚 / 视频数取大")
    p5 = write(os.path.join(tmp, "d.csv"),
               "昵称,sec_uid,合集名,搜索词,首次发现时间,最近发现时间,视频数\r\n"
               ",MS4wLjABAAAAaa01,,,2026-08-01 00:00:00,2026-09-20 10:00:00,9\r\n")
    T.upload_records(T.load_records(p5), CFG, client=r1)
    meta = json.loads(r1.hget("the_meta", "MS4wLjABAAAAaa01"))
    check("旧昵称保留=小明", meta.get("nickname") == "小明", meta)
    check("旧合集名保留", meta.get("mix_name") == "漫剧,合集", meta)
    check("first_seen 取最早=08-01", meta.get("first_seen") == "2026-08-01 00:00:00", meta)
    check("last_seen 取最晚=09-20", meta.get("last_seen") == "2026-09-20 10:00:00", meta)
    check("video_count 取大=9", meta.get("video_count") == 9, meta)

    # ---------- 6. sec_uid 归一化 ----------
    print("\n[6] sec_uid 归一化")
    check("URL 包裹可抠出",
          T.normalize_sec_uid("https://www.douyin.com/user/MS4wLjABAAAAzz09?from=1") == "MS4wLjABAAAAzz09")
    check("引号+行尾逗号可清洗",
          T.normalize_sec_uid('"MS4wLjABAAAAzz09",') == "MS4wLjABAAAAzz09")
    check("空白行不算有效 uid", not T.is_probable_sec_uid(""))

    # ---------- 7. 配置默认值 ----------
    print("\n[7] 配置默认值")
    cfg = T.load_config()
    check("默认 set_key 与原版一致", cfg["set_key"] == "douyin_topic_search_sec_id", cfg["set_key"])
    check("默认 meta_key 生效", cfg["meta_key"] == "douyin_topic_search_sec_meta", cfg["meta_key"])

    print("\n================ 结果：PASS %d / FAIL %d ================" % (PASS, FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
