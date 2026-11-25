#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
抖音 sec_uid 上传工具 v2（含作者元数据）
=====================================================================
与原版 douyin_sec_id_upload_tool.exe 的「去重入库」行为对齐，界面同样回三样：
    文件有效数量 / 本次新增 / 集合总数

相对原版的改动（只增不改语义）：
  1) 输入从「纯 sec_uid 的 txt」升级为「扩展导出的 CSV」——CSV 本来就有
     昵称 / 合集名 / 视频数 / 首次发现 / 最近发现，顺手写进 Redis 的一个 Hash，
     日后做「存量清洗 / 统计」时不用再回抖音逐页抓。
  2) 兼容旧的 txt（每行一个 sec_uid）：此时只写 set，不写元数据。
  3) Redis 连接信息外置到 config.ini / 环境变量，不写死在代码里。

写库目标：
  - set  : douyin_topic_search_sec_id      （与原版同一个集合，避免数据分叉）
  - hash : douyin_topic_search_sec_meta    （新增，作者元数据）

配置读取优先级： 命令行参数 > 环境变量 > 同级 config.ini > 同级 config.json > 默认值
环境变量：DOUYIN_REDIS_HOST / DOUYIN_REDIS_PORT / DOUYIN_REDIS_DB /
          DOUYIN_REDIS_PASSWORD / DOUYIN_SET_KEY / DOUYIN_META_KEY

命令行用法：
  py upload_tool_v2.py                 # 打开图形界面
  py upload_tool_v2.py --cli 文件      # 无界面，处理完打印结果（可接批处理）
  py upload_tool_v2.py --check         # 只测 Redis 连通性
"""

import os
import re
import io
import sys
import csv
import json
import configparser

DEFAULT_SET_KEY = "douyin_topic_search_sec_id"
DEFAULT_META_KEY = "douyin_topic_search_sec_meta"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = "6379"
DEFAULT_DB = "0"

# ---- CSV 表头别名：按「归一化后的表头名」定位列，兼容两种导出入口 ----
#   浮层面板(content.js): 昵称, sec_uid, 合集名, 搜索词, 首次发现时间, 最近发现时间, 视频数
#   弹窗(popup.js):      昵称, sec_uid, 抖音号, uid, 合集名, 合集ID, 示例链接, 视频数
FIELD_ALIASES = {
    "sec_uid": ["sec_uid", "secuid", "sec uid", "作者secuid", "sec用户id"],
    "nickname": ["昵称", "nickname", "作者昵称", "名称"],
    "mix_name": ["合集名", "合集名称", "mix", "mix_name", "mixname"],
    "keyword": ["搜索词", "keyword", "关键词"],
    "first_seen": ["首次发现时间", "first_seen", "firstseen"],
    "last_seen": ["最近发现时间", "last_seen", "lastseen"],
    "video_count": ["视频数", "video_count", "videocount", "视频条数"],
}


# ============================ 小工具 ============================

class _NullStream(object):
    """打包成 --windowed exe 时 sys.stdout / sys.stderr 为 None，写它们会抛
    RuntimeError("lost sys.stdout")，这里给个哑对象兜底。"""
    encoding = "utf-8"

    def write(self, *args, **kwargs):
        return 0

    def flush(self):
        pass

    def isatty(self):
        return False


def ensure_std_streams():
    if sys.stdout is None:
        sys.stdout = _NullStream()
    if sys.stderr is None:
        sys.stderr = _NullStream()


def app_dir():
    """脚本/exe 所在目录（配置、日志都放这里）。"""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def _norm_key(s):
    return re.sub(r"[\s_\-]+", "", str(s or "")).lower()


_ALIAS_MAP = {}
for _field, _names in FIELD_ALIASES.items():
    for _n in _names:
        _ALIAS_MAP[_norm_key(_n)] = _field


def normalize_sec_uid(raw):
    """从任意文本里抠出 sec_uid：兼容被引号/逗号/整行 URL 包裹的情况。"""
    s = str(raw or "").strip().strip('"').strip("'").strip()
    if not s:
        return ""
    m = re.search(r"(MS4wLjABAAAA[0-9A-Za-z_\-]+)", s)
    if m:
        return m.group(1)
    return s.rstrip(",;").strip()


def is_probable_sec_uid(s):
    return bool(s) and 8 <= len(s) <= 200 and " " not in s and "\t" not in s


def to_int(v):
    try:
        return int(float(str(v).strip() or 0))
    except (TypeError, ValueError):
        return 0


def min_str(a, b):
    """取较早的时间串；格式统一的 YYYY-MM-DD HH:MM:SS 可直接按字典序比较。"""
    a, b = (a or "").strip(), (b or "").strip()
    if not a:
        return b
    if not b:
        return a
    return a if a <= b else b


def max_str(a, b):
    a, b = (a or "").strip(), (b or "").strip()
    if not a:
        return b
    if not b:
        return a
    return a if a >= b else b


def _read_text(path):
    """按常见中文/UTF8 编码依次尝试读取。"""
    data = open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-8", "gbk", "gb18030"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", "replace")


# ============================ 解析输入 ============================

def _cell(row, col):
    if not col:
        return ""
    return (row.get(col, "") or "").strip()


def load_csv_records(path):
    """解析 CSV；返回记录列表；若找不到 sec_uid 列则返回 None（交给 txt 兜底）。"""
    reader = csv.DictReader(io.StringIO(_read_text(path)))
    if not reader.fieldnames:
        return None
    colmap = {}
    for h in reader.fieldnames:
        f = _ALIAS_MAP.get(_norm_key(h))
        if f and f not in colmap:
            colmap[f] = h
    if "sec_uid" not in colmap:
        return None
    records = []
    for row in reader:
        uid = normalize_sec_uid(_cell(row, colmap["sec_uid"]))
        if not is_probable_sec_uid(uid):
            continue
        records.append({
            "sec_uid": uid,
            "nickname": _cell(row, colmap.get("nickname")),
            "mix_name": _cell(row, colmap.get("mix_name")),
            "keyword": _cell(row, colmap.get("keyword")),
            "first_seen": _cell(row, colmap.get("first_seen")),
            "last_seen": _cell(row, colmap.get("last_seen")),
            "video_count": to_int(_cell(row, colmap.get("video_count"))),
        })
    return records


def load_txt_records(path):
    """每行一个 sec_uid（兼容旧习惯）：无元数据。"""
    records = []
    for line in _read_text(path).splitlines():
        uid = normalize_sec_uid(line)
        if is_probable_sec_uid(uid):
            records.append({
                "sec_uid": uid,
                "nickname": "", "mix_name": "", "keyword": "",
                "first_seen": "", "last_seen": "", "video_count": 0,
            })
    return records


def dedupe_merge(records):
    """按 sec_uid 去重，并把同一作者的元数据合并（字段互补、视频数取大、时间取早/晚）。"""
    out = {}
    order = []
    for r in records:
        uid = r["sec_uid"]
        if uid not in out:
            out[uid] = dict(r)
            order.append(uid)
            continue
        cur = out[uid]
        for k in ("nickname", "mix_name", "keyword"):
            if not cur.get(k) and r.get(k):
                cur[k] = r[k]
        cur["video_count"] = max(to_int(cur.get("video_count")), to_int(r.get("video_count")))
        cur["first_seen"] = min_str(cur.get("first_seen"), r.get("first_seen"))
        cur["last_seen"] = max_str(cur.get("last_seen"), r.get("last_seen"))
    return [out[u] for u in order]


def load_records(path):
    """统一入口：.csv 走 CSV 解析（失败退回按行读），其余按 txt 逐行读。"""
    if path.lower().endswith(".csv"):
        records = load_csv_records(path)
        if records is None:
            records = load_txt_records(path)
    else:
        records = load_txt_records(path)
    return dedupe_merge(records)


# ============================ 配置 ============================

def load_config(overrides=None):
    cfg = {
        "host": DEFAULT_HOST,
        "port": DEFAULT_PORT,
        "db": DEFAULT_DB,
        "password": "",
        "set_key": DEFAULT_SET_KEY,
        "meta_key": DEFAULT_META_KEY,
        "_source": "默认值",
    }
    base = app_dir()

    ini_path = os.path.join(base, "config.ini")
    if os.path.isfile(ini_path):
        try:
            cp = configparser.ConfigParser()
            cp.read(ini_path, encoding="utf-8")
            if cp.has_section("redis"):
                sec = cp["redis"]
                cfg["host"] = sec.get("host", cfg["host"])
                cfg["port"] = sec.get("port", cfg["port"])
                cfg["db"] = sec.get("db", cfg["db"])
                cfg["password"] = sec.get("password", cfg["password"])
            if cp.has_section("keys"):
                ks = cp["keys"]
                cfg["set_key"] = ks.get("set_key", cfg["set_key"])
                cfg["meta_key"] = ks.get("meta_key", cfg["meta_key"])
            cfg["_source"] = ini_path
        except Exception as e:  # 配置写错不该让工具起不来
            cfg["_source"] = "config.ini 解析失败：" + str(e)

    json_path = os.path.join(base, "config.json")
    if os.path.isfile(json_path) and cfg["_source"] == "默认值":
        try:
            with open(json_path, "r", encoding="utf-8") as f:
                j = json.load(f)
            for k in ("host", "port", "db", "password", "set_key", "meta_key"):
                if k in j and j[k] not in (None, ""):
                    cfg[k] = j[k]
            cfg["_source"] = json_path
        except Exception as e:
            cfg["_source"] = "config.json 解析失败：" + str(e)

    env_map = {
        "DOUYIN_REDIS_HOST": "host",
        "DOUYIN_REDIS_PORT": "port",
        "DOUYIN_REDIS_DB": "db",
        "DOUYIN_REDIS_PASSWORD": "password",
        "DOUYIN_SET_KEY": "set_key",
        "DOUYIN_META_KEY": "meta_key",
    }
    env_hit = [k for k in env_map if os.environ.get(k)]
    for env_name, field in env_map.items():
        if os.environ.get(env_name):
            cfg[field] = os.environ[env_name]
    if env_hit:
        cfg["_source"] = "环境变量(" + ",".join(env_hit) + ")"

    if overrides:
        for k, v in overrides.items():
            if v not in (None, ""):
                cfg[k] = v
        cfg["_source"] = "命令行参数"
    return cfg


def connect(cfg):
    try:
        import redis  # type: ignore[import-not-found]
    except ImportError:
        raise RuntimeError("未安装 redis 库，请先执行：py -m pip install redis")
    return redis.Redis(
        host=cfg["host"],
        port=int(cfg["port"] or 0),
        db=int(cfg["db"] or 0),
        password=(cfg.get("password") or None),
        socket_connect_timeout=8,
        socket_timeout=20,
        decode_responses=True,
    )


# ============================ 写库逻辑 ============================

def _build_meta(rec):
    """只把非空字段放进元数据，避免写一堆空值。"""
    meta = {}
    for src, dst in (("nickname", "nickname"), ("mix_name", "mix_name"), ("keyword", "keyword")):
        if rec.get(src):
            meta[dst] = rec[src]
    if to_int(rec.get("video_count")):
        meta["video_count"] = to_int(rec["video_count"])
    if rec.get("first_seen"):
        meta["first_seen"] = rec["first_seen"]
    if rec.get("last_seen"):
        meta["last_seen"] = rec["last_seen"]
    return meta


def _load_old_meta(client, meta_key, uid):
    raw = client.hget(meta_key, uid)
    if not raw:
        return {}
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8", "replace")
    try:
        return json.loads(raw)
    except (ValueError, TypeError):
        return {}


def _merge_meta(old, new):
    merged = dict(old)
    for k in ("nickname", "mix_name", "keyword"):
        if new.get(k):
            merged[k] = new[k]
    if new.get("video_count"):
        merged["video_count"] = max(to_int(old.get("video_count")), to_int(new.get("video_count")))
    fs = min_str(old.get("first_seen"), new.get("first_seen"))
    ls = max_str(old.get("last_seen"), new.get("last_seen"))
    if fs:
        merged["first_seen"] = fs
    if ls:
        merged["last_seen"] = ls
    return merged


def upload_records(records, cfg, client=None, progress=None):
    """核心写库：sec_uid 写 set，元数据合并写 hash。

    返回 dict：valid 文件有效数量 / added 本次新增 / total 集合总数 / meta_written 元数据条数
    """
    if client is None:
        client = connect(cfg)
    set_key = cfg["set_key"]
    meta_key = cfg["meta_key"]
    added = 0
    meta_written = 0
    total_n = len(records)

    for idx, rec in enumerate(records, 1):
        uid = rec["sec_uid"]
        added += int(bool(client.sadd(set_key, uid)))
        meta = _build_meta(rec)
        if meta:
            old = _load_old_meta(client, meta_key, uid)
            client.hset(meta_key, uid, json.dumps(_merge_meta(old, meta), ensure_ascii=False))
            meta_written += 1
        if progress and (idx % 200 == 0 or idx == total_n):
            progress(idx, total_n)

    return {
        "valid": len(records),
        "added": added,
        "total": int(client.scard(set_key)),
        "meta_written": meta_written,
    }


# ============================ 图形界面 ============================

def run_gui():
    import threading
    import queue
    import tkinter as tk
    from tkinter import filedialog, messagebox

    cfg = load_config()

    root = tk.Tk()
    root.title("抖音 sec_uid 上传工具 v2（含元数据）")
    root.geometry("680x460")
    root.minsize(620, 420)

    tk.Label(root, text="选择要上传的文件（.csv 优先；也支持每行一个 sec_uid 的 .txt）",
             anchor="w").pack(fill="x", padx=12, pady=(12, 4))

    file_row = tk.Frame(root)
    file_row.pack(fill="x", padx=12)
    file_var = tk.StringVar()
    tk.Entry(file_row, textvariable=file_var).pack(side="left", fill="x", expand=True)

    def pick_file():
        p = filedialog.askopenfilename(
            title="选择采集导出文件",
            filetypes=[("CSV / TXT", "*.csv *.txt"), ("CSV", "*.csv"), ("文本", "*.txt"), ("全部", "*.*")],
        )
        if p:
            file_var.set(p)

    tk.Button(file_row, text="选择文件", command=pick_file).pack(side="left", padx=(8, 0))

    cfg_var = tk.StringVar()
    cfg_var.set("配置来源：%s    Redis：%s:%s/%s    set=%s    hash=%s" % (
        cfg["_source"], cfg["host"], cfg["port"], cfg["db"], cfg["set_key"], cfg["meta_key"]))
    tk.Label(root, textvariable=cfg_var, anchor="w", fg="#555").pack(fill="x", padx=12, pady=(8, 0))

    btn_row = tk.Frame(root)
    btn_row.pack(fill="x", padx=12, pady=8)

    status_var = tk.StringVar(value="就绪")
    tk.Label(root, textvariable=status_var, anchor="w", fg="#0a5").pack(fill="x", padx=12)

    out = tk.Text(root, height=14, wrap="word")
    out.pack(fill="both", expand=True, padx=12, pady=(6, 12))
    out.configure(state="disabled")

    def log(s):
        out.configure(state="normal")
        out.insert("end", s + "\n")
        out.see("end")
        out.configure(state="disabled")

    q = queue.Queue()

    def worker_run():
        try:
            path = file_var.get().strip()
            if not path or not os.path.isfile(path):
                raise RuntimeError("请先选择一个有效的文件")
            records = load_records(path)
            if not records:
                raise RuntimeError("文件里没解析到任何 sec_uid")
            q.put(("log", "解析完成：文件有效数量 = %d（已去重）" % len(records)))
            q.put(("log", "正在写入 Redis ……"))
            res = upload_records(records, cfg, progress=lambda i, n: q.put(("status", "写入中 %d/%d" % (i, n))))
            q.put(("done", res))
        except Exception as e:
            q.put(("error", str(e)))

    def worker_check():
        try:
            c = connect(cfg)
            c.ping()
            q.put(("log", "连接成功：%s:%s/%s  set 现有 %d 个" % (
                cfg["host"], cfg["port"], cfg["db"], int(c.scard(cfg["set_key"])))))
            q.put(("status", "连接正常"))
        except Exception as e:
            q.put(("error", "连接失败：" + str(e)))

    busy = {"v": False}

    def pump():
        try:
            while True:
                kind, payload = q.get_nowait()
                if kind == "log":
                    log(payload)
                elif kind == "status":
                    status_var.set(payload)
                elif kind == "done":
                    log("")
                    log("===== 处理结果 =====")
                    log("文件有效数量：%d" % payload["valid"])
                    log("本次新增：%d" % payload["added"])
                    log("集合总数：%d" % payload["total"])
                    log("元数据写入：%d 条" % payload["meta_written"])
                    status_var.set("完成")
                    busy["v"] = False
                    start_btn.configure(state="normal")
                elif kind == "error":
                    log("[错误] " + str(payload))
                    status_var.set("出错")
                    busy["v"] = False
                    start_btn.configure(state="normal")
        except queue.Empty:
            pass
        root.after(120, pump)

    def start():
        if busy["v"]:
            return
        busy["v"] = True
        start_btn.configure(state="disabled")
        status_var.set("处理中……")
        threading.Thread(target=worker_run, daemon=True).start()

    def test_conn():
        threading.Thread(target=worker_check, daemon=True).start()

    tk.Button(btn_row, text="测试连接", command=test_conn).pack(side="left")
    start_btn = tk.Button(btn_row, text="开始处理", command=start)
    start_btn.pack(side="left", padx=8)
    tk.Button(btn_row, text="清空输出", command=lambda: (out.configure(state="normal"),
             out.delete("1.0", "end"), out.configure(state="disabled"))).pack(side="left")

    log("工具就绪。写库目标 set = %s，元数据 hash = %s" % (cfg["set_key"], cfg["meta_key"]))
    log("提示：连不上 Redis 时，请确认 config.ini 里的连接信息是否正确。")
    pump()
    root.mainloop()


# ============================ 命令行 ============================

def main(argv):
    ensure_std_streams()
    args = [a for a in argv[1:]]
    if "--help" in args or "-h" in args:
        print(__doc__)
        return 0

    cfg = load_config()

    if "--check" in args:
        try:
            c = connect(cfg)
            c.ping()
            print("连接成功：%s:%s/%s  set=%s 现有 %d 个" % (
                cfg["host"], cfg["port"], cfg["db"], cfg["set_key"], int(c.scard(cfg["set_key"]))))
            return 0
        except Exception as e:
            print("连接失败：" + str(e), file=sys.stderr)
            return 2

    if "--cli" in args:
        idx = args.index("--cli")
        rest = args[idx + 1:]
        if not rest:
            print("用法：py upload_tool_v2.py --cli <文件>", file=sys.stderr)
            return 1
        path = rest[0]
        records = load_records(path)
        if not records:
            print("文件里没解析到任何 sec_uid", file=sys.stderr)
            return 1
        res = upload_records(records, cfg)
        print(json.dumps({
            "文件有效数量": res["valid"],
            "本次新增": res["added"],
            "集合总数": res["total"],
            "元数据写入": res["meta_written"],
        }, ensure_ascii=False, indent=2))
        return 0

    run_gui()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
