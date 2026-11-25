const fs = require("fs");
const html = fs.readFileSync(process.argv[2], "utf8");
const code = html.match(/<script>([\s\S]*?)<\/script>/)[1];

const els = {};
const tbodies = {};
function mkEl(id) {
  const e = {
    id, value: "", textContent: "", innerHTML: "", style: {}, files: [],
    listeners: {},
    addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); },
    querySelector() { return (tbodies[id] = tbodies[id] || mkEl(id + ":tbody")); },
    classList: { add() {}, remove() {}, contains() { return false; } },
    appendChild() {}, removeChild() {}, remove() {}, click() {}, select() {}
  };
  return e;
}
global.document = {
  getElementById(id) { return els[id] || (els[id] = mkEl(id)); },
  querySelectorAll() { return []; },
  createElement() { return mkEl("tmp"); },
  body: { appendChild() {}, removeChild() {} }
};
const store = {};
global.localStorage = {
  getItem(k) { return k in store ? store[k] : null; },
  setItem(k, v) { store[k] = String(v); },
  removeItem(k) { delete store[k]; }
};
global.navigator = {};
global.Blob = function () {};
global.URL = { createObjectURL() { return "blob:x"; }, revokeObjectURL() {} };
global.FileReader = function () {
  this.readAsText = (f) => { this.result = f.__text; this.onload && this.onload(); };
};

new Function(code)();

function fire(id, type, ev) {
  (els[id].listeners[type] || []).forEach((f) => f(ev));
}
function importCsv(text) {
  fire("fileInput", "change", { target: { files: [{ __text: text }] } });
}
function save() { fire("btnSave", "click", {}); }

const HEAD = "昵称,sec_uid,合集名,搜索词,首次发现时间,最近发现时间,视频数";
const csv1 = HEAD + "\n" +
  "AI漫剧小张,MS4wLjABAAAAa,重生逆袭剧场,AI漫剧 逆袭,2026-09-28 10:00:00,2026-09-28 10:00:00,3\n" +
  "汪汪队立大功,MS4wLjABAAAAb,儿童动画,AI动画 儿童,2026-09-28 10:00:00,2026-09-28 10:00:00,5\n" +
  "真人短剧王,MS4wLjABAAAAc,豪门恩怨,AI短剧 豪门,2026-09-28 10:00:00,2026-09-28 10:00:00,1";

function snap(label) {
  console.log("--- " + label + " ---");
  console.log("tagTarget:", els.tagTarget.textContent, "| tagHigh:", els.tagHigh.textContent, "| tagTotal:", els.tagTotal.textContent);
  console.log("r6 新增作者:", els.r6.textContent, "| r7h 高置信:", els.r7h.textContent, "| r7 重点:", els.r7.textContent);
  console.log("csvInfo:", els.csvInfo.textContent);
  console.log("table rows:", (tbodies.resultTable ? (tbodies.resultTable.innerHTML.match(/<tr/g) || []).length : 0));
}

importCsv(csv1);
snap("第1次导入（池为空，无人高置信）");

save();
snap("第1次保存后（池=3）");

importCsv(csv1);
snap("第2次导入（count=1，仍不够高置信）");

save();
snap("第2次保存后（池 count=2）");

importCsv(csv1);
snap("第3次导入（count=2，应出现高置信）");

// 校验导出高置信不报错
fire("btnExportHigh", "click", {});
fire("btnExportCsv", "click", {});
console.log("export handlers ran without throwing");

// 校验汇报文案
console.log("--- 汇报文案 ---");
console.log(els.reportText.textContent);

// 校验池结构
const pool = JSON.parse(store["dyaccount_plus_pool"]);
console.log("--- 池结构 ---");
console.log(JSON.stringify(pool, null, 0));
const ok = els.tagHigh.textContent.indexOf("高置信 1") === 0 && els.tagTarget.textContent === "重点 1";
console.log(ok ? "ASSERT OK" : "ASSERT FAIL");