(function (root) {
  "use strict";

  var SEC_UID_RE = /MS4wLjABAAAA[A-Za-z0-9_-]{20,}/;
  var AI_DECLARE_TEXTS = [
    "内容由AI生成",
    "内容由 AI 生成",
    "内容由Ai生成",
    "作者声明：内容由AI生成",
    "作者声明:内容由AI生成",
    "作者声明：内容由 AI 生成"
  ];
  var AI_SUSPECT_TEXTS = ["疑似使用AI", "疑似使用 AI", "疑似使用人工智能"];

  /* 字段别名唯一来源：content/adapter.js 的 PICK_ALIAS；
   * 加载序由 manifest.json content_scripts[1] 保证（adapter 在 parse 之前）。
   * 本文件不自带副本，避免出现第二张真源。 */
  var PK = root.DyAdapter.PICK_ALIAS;

  /* 记录「真实来源页」：采集发生时的页面 URL。
   * 精选推流 -> /jingxuan（可能带 modal_id）；搜索页 -> /search/关键词；播放页 -> /video/ID。
   * 用于事后归因「这条作者是从哪条链路撞到的」，替代原先硬编码的 jingxuan 假链接。 */
  function pageUrl() {
    try {
      return (typeof location !== "undefined" && location.href) || "";
    } catch (e) {
      return "";
    }
  }

  function pick(obj, keys, fallback) {
    if (!obj || typeof obj !== "object") return fallback;
    for (var i = 0; i < keys.length; i++) {
      if (obj[keys[i]] != null && obj[keys[i]] !== "") return obj[keys[i]];
    }
    return fallback;
  }

  function firstUrl(cover) {
    if (!cover) return "";
    if (typeof cover === "string") return cover;
    var list = cover.url_list || cover.urlList;
    if (Array.isArray(list) && list.length) return String(list[0]);
    return cover.uri || "";
  }

  function looksLikeAweme(node) {
    if (!node || typeof node !== "object" || Array.isArray(node)) return false;
    var id = node.aweme_id || node.awemeId;
    if (!id) return false;
    return !!(
      node.author ||
      node.authorInfo ||
      node.author_user_id ||
      node.authorUserId ||
      node.desc != null ||
      node.mix_info ||
      node.mixInfo
    );
  }

  function collectAwemes(data, out, seen, depth) {
    if (data == null || depth > 14) return;
    if (typeof data !== "object") return;
    if (seen.has(data)) return;
    seen.add(data);

    if (Array.isArray(data)) {
      for (var i = 0; i < data.length; i++) collectAwemes(data[i], out, seen, depth + 1);
      return;
    }

    if (looksLikeAweme(data)) {
      var id = String(data.aweme_id || data.awemeId);
      if (id && !out.map[id]) {
        out.map[id] = data;
        out.list.push(data);
      }
    }

    for (var key in data) {
      if (!Object.prototype.hasOwnProperty.call(data, key)) continue;
      collectAwemes(data[key], out, seen, depth + 1);
    }
  }

  function extractMix(aweme) {
    var mix = aweme.mix_info || aweme.mixInfo || null;
    if (mix && typeof mix === "object") {
      var mixId = pick(mix, PK.MIX_ID, "");
      var mixName = pick(mix, PK.MIX_NAME, "");
      if (mixId || mixName) {
        return {
          mixId: String(mixId || ""),
          mixName: String(mixName || "")
        };
      }
    }
    var directId = aweme.mix_id || aweme.mixId;
    if (directId) {
      return { mixId: String(directId), mixName: String(aweme.mix_name || aweme.mixName || "") };
    }
    return null;
  }

  function blobHasDeclare(text) {
    for (var i = 0; i < AI_DECLARE_TEXTS.length; i++) {
      if (text.indexOf(AI_DECLARE_TEXTS[i]) !== -1) return true;
    }
    return false;
  }

  function blobHasSuspectOnly(text) {
    if (blobHasDeclare(text)) return false;
    for (var i = 0; i < AI_SUSPECT_TEXTS.length; i++) {
      if (text.indexOf(AI_SUSPECT_TEXTS[i]) !== -1) return true;
    }
    return false;
  }

  function extractAigc(aweme) {
    var aigc = aweme.aigc_info || aweme.aigcInfo || {};
    var labelType = Number(
      pick(aigc, PK.AIGC_LABEL_TYPE, 0) || 0
    );
    var createdByAi = !!(aigc.created_by_ai || aigc.createdByAi);
    var blob = "";
    try {
      blob = JSON.stringify(aweme);
    } catch (e) {
      try {
        blob = JSON.stringify(aigc);
      } catch (e2) {}
    }
    var declaredText = blobHasDeclare(blob);
    var declared = declaredText || labelType === 1 || createdByAi;
    return {
      labelType: labelType,
      createdByAi: createdByAi,
      declared: declared,
      suspectOnly: !declared && (blobHasSuspectOnly(blob) || labelType === 3),
      reason: declared
        ? declaredText
          ? "text:内容由AI生成"
          : labelType === 1
            ? "aigc_label_type=1"
            : "created_by_ai"
        : ""
    };
  }

  function extractAuthor(aweme) {
    var author = aweme.author || aweme.authorInfo || {};
    var secUid =
      pick(author, PK.AUTHOR_SEC_UID, "") ||
      pick(aweme, PK.AWEME_SEC_UID, "");
    secUid = String(secUid || "");
    if (!SEC_UID_RE.test(secUid)) {
      var m = String(JSON.stringify(author)).match(SEC_UID_RE);
      secUid = m ? m[0] : "";
    }
    var avatar =
      firstUrl(author.avatar_thumb || author.avatarThumb) ||
      firstUrl(author.avatar_medium || author.avatarMedium) ||
      String(author.avatarUri || author.avatar_uri || "");
    return {
      secUid: secUid,
      uid: String(pick(author, PK.AUTHOR_UID, aweme.author_user_id || aweme.authorUserId || "")),
      nickname: String(pick(author, PK.AUTHOR_NICKNAME, "")),
      uniqueId: String(pick(author, PK.AUTHOR_UNIQUE_ID, "")),
      avatar: avatar
    };
  }

  function toRecord(aweme) {
    var author = extractAuthor(aweme);
    var mix = extractMix(aweme);
    var aigc = extractAigc(aweme);
    var awemeId = String(aweme.aweme_id || aweme.awemeId || "");
    return {
      awemeId: awemeId,
      /* 视频发布时刻（秒级 unix）。仅用于「上新高峰时段」分析，不进 CSV 的 8 列。 */
      createTime: Number(pick(aweme, PK.CREATE_TIME, 0) || 0),
      desc: String(aweme.desc || aweme.description || "").slice(0, 200),
      author: author,
      mix: mix,
      aigc: aigc,
      hasMix: !!mix,
      hasAiDeclare: !!aigc.declared,
      url: pageUrl() || (awemeId ? "https://www.douyin.com/jingxuan?modal_id=" + awemeId : "")
    };
  }

  function parsePayload(jsonText) {
    var data;
    try {
      data = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText;
    } catch (e) {
      return [];
    }
    var bag = { list: [], map: Object.create(null) };
    collectAwemes(data, bag, new Set(), 0);
    var records = [];
    for (var i = 0; i < bag.list.length; i++) {
      records.push(toRecord(bag.list[i]));
    }
    return records;
  }

  function parseRenderDataText(raw) {
    if (!raw) return [];
    var text = String(raw).trim();
    try {
      text = decodeURIComponent(text);
    } catch (e) {}
    return parsePayload(text);
  }

  function matchFilters(record, requireMix, requireAi) {
    if (requireMix && !record.hasMix) return false;
    if (requireAi && !record.hasAiDeclare) return false;
    return !!(record.author && record.author.secUid);
  }

  function fromDomHints(info) {
    if (!info || !info.secUid) return null;
    return {
      awemeId: info.awemeId || "",
      createTime: 0, // DOM 兜底链路拿不到发布时刻
      desc: info.desc || "",
      author: {
        secUid: info.secUid,
        uid: info.uid || "",
        nickname: info.nickname || "",
        uniqueId: info.uniqueId || "",
        avatar: info.avatar || ""
      },
      mix: info.mixName || info.mixId ? { mixId: info.mixId || "", mixName: info.mixName || "" } : null,
      aigc: {
        labelType: 1,
        createdByAi: true,
        declared: true,
        suspectOnly: false,
        reason: "dom:内容由AI生成"
      },
      hasMix: !!(info.hasMix || info.mixName || info.mixId),
      hasAiDeclare: true,
      url: pageUrl() || (info.awemeId ? "https://www.douyin.com/jingxuan?modal_id=" + info.awemeId : "")
    };
  }

  root.DyAccountParse = {
    SEC_UID_RE: SEC_UID_RE,
    AI_DECLARE_TEXTS: AI_DECLARE_TEXTS,
    parsePayload: parsePayload,
    parseRenderDataText: parseRenderDataText,
    toRecord: toRecord,
    matchFilters: matchFilters,
    fromDomHints: fromDomHints,
    blobHasDeclare: blobHasDeclare
  };
})(typeof globalThis !== "undefined" ? globalThis : window);
