/* =========================================================================
 * content/adapter.js —— P3-a + P3-b：抖音页面适配点集中登记（唯一来源）
 *
 * 本文件只放「会随抖音改版而变」的常量：
 *   - DOM 选择器 / 扫描域 / 兜底链
 *   - 页面文本锚点（按钮名、板块标题、tab 名）
 *   - URL 形态与判定正则
 *   - 平台属性名、类名判定
 *   - 接口字段别名（P3-b 起，parse.js pick() 查表用）
 * 不放：业务词池、行为参数、消息协议。
 *
 * 加载顺序（manifest.json content_scripts[1] 第 40 行）：
 *   ["shared.js", "content/adapter.js", "content/parse.js", "content/content.js"]
 *   该组 world = ISOLATED；content_scripts[0]（MAIN）不加载本文件。
 *
 * 护栏：
 *   1) 值逐字搬自 content.js 原内联字面量，不改一个字符、不改顺序；
 *   2) 10 个数组值各套一层 Object.freeze（Object.freeze 本身是浅冻结）；
 *   3) 5 条正则均无 g 标志，test() 不推进 lastIndex，跨调用复用安全。
 *
 * 不收录（刻意留在原处）：
 *   - 380 行播放器根节点链（含链尾 document.body），跨 API 短路语义不改为数组遍历；
 *   - 141 / 152 / 1307 三条带捕获组的正则，与同前缀的判定正则不同源；
 *   - 421 / 1313 的 mixName 字段值、783 的面板兜底文案，属导出/展示值不是页面锚点。
 * ========================================================================= */
(function (root) {
  "use strict";

  root.DyAdapter = Object.freeze({
    /* ---------- 1. DOM 选择器 ---------- */
    RENDER_DATA_IDS: Object.freeze(["RENDER_DATA", "__NEXT_DATA__"]),
    USER_LINK_SEL: 'a[href*="/user/"]',
    MIX_LINK_SEL: 'a[href*="/collection/"], a[href*="/mix/"]',
    SCROLL_ROOT_HINTS: Object.freeze([
      '[id*="search-result"]',
      '[class*="search-result"]',
      '[class*="SearchResult"]',
      '[class*="waterfall"]',
      "#douyin-right-container",
      "main",
      "#root"
    ]),
    LOAD_MORE_TEXTS: Object.freeze(["加载更多", "查看更多", "点击加载"]),
    LOAD_MORE_SCAN_SEL: "button, div, span, p",
    NEXT_PLAYER_SELS: Object.freeze([
      '[data-e2e="video-switch-next"]',
      '[class*="xgplayer-playswitch-next"]',
      '[class*="slide-next"]',
      '[class*="swiper-button-next"]',
      '[class*="arrowDown"]',
      '[class*="arrow-down"]',
      'button[aria-label*="下一个"]',
      'div[aria-label*="下一个"]',
      'div[aria-label*="下一条"]'
    ]),
    VIDEO_SEL: "video",
    CARD_CLOSEST_SELS: Object.freeze([
      "li",
      "article",
      '[class*="card"]',
      '[class*="Card"]',
      '[class*="item"]'
    ]),
    VIDEO_LINK_SEL: 'a[href*="/video/"]',
    TABLIST_SELS: Object.freeze([
      '[role="tablist"]',
      '[class*="semi-tabs-tab-list"]',
      '[class*="semi-tabs-bar"]'
    ]),
    TAB_SELS: Object.freeze(['[role="tab"]', '[class*="semi-tabs-tab"]']),
    TAB_FALLBACK_SCAN_SEL: "span,div,li,button",
    TAB_CLOSEST_SEL: '[role="tab"], [class*="tab"]',
    RELATED_SCOPE_SEL: "div,span,h2,h3,p",
    RELATED_ITEM_SEL: "div,span,a,li",
    IMG_SEL: "img",
    CLICKABLE_SCAN_SEL: "div,span,a,li,button,p",

    /* ---------- 2. 页面文本锚点 ---------- */
    CAPTCHA_TEXTS: Object.freeze([
      "请选择所有符合",
      "拖拽到",
      "请完成验证",
      "拖动滑块",
      "向右滑动",
      "安全验证"
    ]),
    LOGOUT_TEXTS: Object.freeze([
      "登录已过期",
      "请重新登录",
      "扫码登录",
      "验证码登录",
      "账号已退出"
    ]),
    MIX_TEXT: "合集",
    RELATED_TITLE_TEXT: "相关搜索",
    MORE_TEXT: "更多",
    SORT_BY_TEXT: "排序依据",
    CONTENT_FORM_TEXT: "内容形式",
    CHANNELS: Object.freeze(["全部", "二次元", "小剧场", "影视"]),
    CHANNEL_ALL_TEXT: "全部",

    /* ---------- 3. URL 形态与正则 ---------- */
    MODAL_ID_RE: /modal_id=/,
    VIDEO_PATH_RE: /\/video\//,
    SEARCH_PATH_RE: /\/search\//,
    JINGXUAN_PATH_RE: /\/jingxuan/,
    JINGXUAN_KEY: "jingxuan",
    SEARCH_URL_BASE: "https://www.douyin.com/search/",

    /* ---------- 4. 平台属性名与类名判定 ---------- */
    TAB_SELECTED_ATTR: "aria-selected",
    ARIA_LABEL_ATTR: "aria-label",
    TAB_ACTIVE_CLASS_RE: /(active|selected)/i,

    /* ---------- 5. 解析字段别名（P3-b：parse.js pick() 的唯一来源） ---------- */
    PICK_ALIAS: Object.freeze({
      MIX_ID: Object.freeze(["mix_id", "mixId", "id", "ids"]),
      MIX_NAME: Object.freeze(["mix_name", "mixName", "name", "title"]),
      AIGC_LABEL_TYPE: Object.freeze(["aigc_label_type", "aigcLabelType", "label_type", "labelType"]),
      AUTHOR_SEC_UID: Object.freeze(["sec_uid", "secUid", "sec_user_id", "secUserId"]),
      AWEME_SEC_UID: Object.freeze(["sec_uid", "secUid"]),
      AUTHOR_UID: Object.freeze(["uid", "id", "user_id", "userId"]),
      AUTHOR_NICKNAME: Object.freeze(["nickname", "nickName", "remarkName"]),
      AUTHOR_UNIQUE_ID: Object.freeze(["unique_id", "uniqueId", "short_id", "shortId", "display_id"]),
      CREATE_TIME: Object.freeze(["create_time", "createTime"])
    })
  });
})(typeof globalThis !== "undefined" ? globalThis : window);
