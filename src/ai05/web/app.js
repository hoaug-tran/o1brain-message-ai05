"use strict";
const $ = (selector) => document.querySelector(selector);
const icons = {
  mail: "M20 4H4a2 2 0 0 0-2 2v12c0 1.1.9 2 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2m0 4-8 5-8-5V6l8 5 8-5z",
  shield: "M12 2 3 6v6c0 5.1 3.8 9.8 9 11 5.2-1.2 9-5.9 9-11V6zm-1 15-4-4 1.4-1.4L11 14.2l5.6-5.6L18 10z",
  spam: "M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2M12 4a6 6 0 1 1 0 12 6 6 0 0 1 0-12m0 2c-.7 0-1.4.2-2 .6l5.4 5.4A4 4 0 0 0 12 6m-3.4 2A4 4 0 0 0 14 13.4z",
  settings: "m19.4 13 .1-1-.1-1 2.1-1.6-2-3.4-2.5 1a7 7 0 0 0-1.7-1L15 3h-4l-.4 3a7 7 0 0 0-1.7 1L6.4 6 4.5 9.4 6.6 11l-.1 1 .1 1-2.1 1.6 1.9 3.4 2.5-1a7 7 0 0 0 1.7 1l.4 3h4l.4-3a7 7 0 0 0 1.7-1l2.5 1 2-3.4zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7",
  edit: "m3 17.3 12-12 3.7 3.7-12 12H3zm14-14 1.7-1.7a1 1 0 0 1 1.4 0l2.3 2.3a1 1 0 0 1 0 1.4L20.7 7z",
  search: "M9.5 3a6.5 6.5 0 1 0 4 11.6L20 21l1-1-6.4-6.5A6.5 6.5 0 0 0 9.5 3m0 2a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9",
  person: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8m0 2c-4 0-8 2-8 4v2h16v-2c0-2-4-4-8-4",
  star: "m12 2 3.1 6.3 6.9 1-5 4.9 1.2 6.9-6.2-3.3-6.2 3.3 1.2-6.9-5-4.9 6.9-1z",
  back: "m12 4 1.4 1.4L7.8 11H21v2H7.8l5.6 5.6L12 20l-8-8z",
  more: "M12 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4m0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4m0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4",
  close: "m6 4.6 6 6 6-6L19.4 6l-6 6 6 6-1.4 1.4-6-6-6 6L4.6 18l6-6-6-6z",
  send: "M2 3v7l14 2-14 2v7l21-9z"
};
function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  const path = document.createElementNS(svg.namespaceURI, "path");
  path.setAttribute("d", icons[name]);
  svg.append(path);
  return svg;
}
for (const node of document.querySelectorAll("[data-icon]")) node.append(icon(node.dataset.icon));
function node(tag, value = "", className = "") {
  const element = document.createElement(tag);
  element.textContent = value;
  element.className = className;
  return element;
}
const paragraph = (value, className = "") => node("p", value, className);
function seedThreads() {
  return [
    {id:"library", name:"Alex", unread:1, trusted:true, messages:[{body:"Hey, I might be 15 minutes late. Can you wait for me outside the library?", time:"09:12", outgoing:false}]},
    {id:"delivery", name:"Giao hàng mẫu", unread:0, messages:[{body:"Your order #48291 has shipped. You can check its status in your account.", time:"08:45", outgoing:false}]},
    {id:"dinner", name:"Sarah", unread:0, messages:[{body:"Are you still coming to dinner tonight? We booked a table for seven.", time:"08:30", outgoing:false},{body:"Yes! See you there.",time:"08:32",outgoing:true}]},
    {id:"otp", name:"Mã xác thực mẫu", unread:1, messages:[{body:"Your verification code is 638291. It will expire in 5 minutes. Do not share this code.",time:"08:20",outgoing:false}]},
    {id:"prize", name:"Nhận thưởng mẫu", unread:1, spam:true, messages:[{body:"Congratulations! You've been selected to receive a $2,500 reward. Reply CLAIM to collect your prize.",time:"07:50",outgoing:false}]},
    {id:"archive", name:"Lịch hẹn mẫu", unread:0, archived:true, messages:[{body:"Reminder: Your dental appointment is tomorrow at 3:30 PM. Please arrive 10 minutes early.",time:"Hôm qua",outgoing:false}]}
  ];
}
let threads = seedThreads();
let route = "inbox";
let activeThread = null;
let returnRoute = "inbox";
let draft = "";
let draftRecipient = "";
let noticeTimer;
function notify(message) {
  clearTimeout(noticeTimer);
  $("#snackbar").textContent = message;
  $("#snackbar").hidden = false;
  noticeTimer = setTimeout(() => { $("#snackbar").hidden = true; }, 3500);
}
function visibleThreads() {
  const query = $("#search").value.trim().toLocaleLowerCase();
  const filter = route === "spam" ? "spam" : $("#filter").value;
  return threads.filter(thread => {
    const included = filter === "spam" ? thread.spam : !thread.spam && (filter === "archive" ? thread.archived : !thread.archived && (filter === "unread" ? thread.unread > 0 : filter === "trusted" ? thread.trusted : true));
    return included && `${thread.name} ${thread.messages.map(item => item.body).join(" ")}`.toLocaleLowerCase().includes(query);
  });
}
function renderInbox() {
  const visible = visibleThreads();
  $("#inbox-title").textContent = route === "spam" ? "Tin nhắn rác" : "Tin nhắn";
  $("#filter").hidden = route === "spam";
  $("#compose").hidden = route === "spam";
  $("#conversation-count").textContent = visible.length.toString();
  $("#conversations").replaceChildren(...visible.map(thread => {
    const row = node("div", "", `conversation${thread.unread ? " unread" : ""}`);
    const open = node("button", "", "open-thread");
    open.type = "button";
    const avatar = node("span", "", "avatar");
    avatar.append(icon(thread.trusted ? "star" : "person"));
    const copy = node("span", "", "thread-copy");
    const latest = thread.messages.at(-1);
    const meta = node("span", "", "thread-meta");
    meta.append(node("span", latest.time));
    if (thread.unread) meta.append(node("span", `${thread.unread} chưa đọc`, "unread-label"));
    copy.append(node("span", thread.name, "thread-name"), node("span", latest.body, "thread-preview"), meta);
    open.append(avatar, copy);
    open.addEventListener("click", () => openChat(`chat/${thread.id}/${route}`));
    const menu = node("button", "", "icon-button row-menu");
    menu.setAttribute("aria-label", `Tùy chọn ${thread.name}`);
    menu.append(icon("more"));
    menu.addEventListener("click", () => openActions(thread));
    row.append(open, menu);
    return row;
  }));
  if (!visible.length) $("#conversations").append(paragraph("Chưa có hội thoại trong mục này.", "empty-state"));
  updateBadge();
}
function updateBadge() {
  const count = threads.filter(thread => thread.spam).reduce((sum, thread) => sum + thread.unread, 0);
  $("#spam-count").textContent = count.toString();
  $("#spam-count").hidden = count === 0;
}
function renderChat() {
  $("#chat-title").textContent = activeThread?.name || "Tin nhắn mới";
  $("#recipient-label").hidden = Boolean(activeThread);
  $("#recipient").value = draftRecipient;
  $("#chat-star").hidden = !activeThread;
  $("#chat-menu").hidden = !activeThread;
  $("#chat-star").setAttribute("aria-pressed", String(Boolean(activeThread?.trusted)));
  $("#chat-star").setAttribute("aria-label", activeThread?.trusted ? "Bỏ ưu tiên" : "Thêm ưu tiên");
  $("#messages").replaceChildren(...(activeThread?.messages || []).map(message => {
    const item = node("div", "", `message${message.outgoing ? " outgoing" : ""}`);
    item.append(paragraph(message.body, "bubble"), node("small", `${message.time}${message.outgoing ? " / Đã gửi trong demo" : ""}`));
    return item;
  }));
  $("#message-input").value = activeThread ? activeThread.draft || "" : draft;
  resizeComposer();
  $("#messages").scrollTop = $("#messages").scrollHeight;
}
function navigate() {
  clearTimeout(noticeTimer);
  $("#snackbar").hidden = true;
  if (location.hash === "#content") return;
  const parts = location.hash.slice(1).split("/");
  route = ["inbox", "check", "spam", "settings", "chat", "compose"].includes(parts[0]) ? parts[0] : "inbox";
  if (route === "chat") {
    activeThread = threads.find(thread => thread.id === parts[1]);
    returnRoute = parts[2] === "spam" ? "spam" : "inbox";
    if (!activeThread) { location.replace(`#${returnRoute}`); return; }
    activeThread.unread = 0;
  } else if (route === "compose") { activeThread = null; returnRoute = "inbox"; }
  const chat = route === "chat" || route === "compose";
  for (const name of ["inbox", "chat", "check", "settings"]) $( `#${name}-view`).hidden = name === "chat" ? !chat : name === "inbox" ? !["inbox", "spam"].includes(route) : name !== route;
  for (const link of document.querySelectorAll("[data-route]")) {
    if (link.dataset.route === (chat ? returnRoute : route)) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  document.querySelector(".bottom-nav").hidden = chat;
  document.querySelector(".app-header").hidden = chat;
  if (chat) renderChat();
  else if (["inbox", "spam"].includes(route)) renderInbox();
  updateBadge();
  $("#content").scrollTop = 0;
}
function openActions(thread) {
  $("#actions-title").textContent = thread.name;
  const actions = [
    [thread.unread ? "Đánh dấu đã đọc" : "Đánh dấu chưa đọc", () => { thread.unread = thread.unread ? 0 : 1; }],
    [thread.trusted ? "Bỏ ưu tiên" : "Thêm vào ưu tiên", () => { thread.trusted = !thread.trusted; }],
    [thread.spam ? "Chuyển về hộp thư" : "Chuyển vào tin rác", () => { thread.spam = !thread.spam; thread.archived = false; }],
    [thread.archived ? "Bỏ lưu trữ" : "Lưu trữ", () => { thread.archived = !thread.archived; thread.spam = false; }],
    ["Kiểm tra nội dung", () => { $("#sms").value = thread.messages.at(-1).body; clearResult(); location.hash = "check"; }]
  ];
  $("#thread-actions").replaceChildren(...actions.map(([label, action]) => {
    const button = node("button", label);
    button.disabled = busy && label === "Kiểm tra nội dung";
    button.addEventListener("click", () => {
      action();
      $("#actions-dialog").close();
      if (["inbox", "spam"].includes(route)) renderInbox();
      else if (route === "chat") renderChat();
      updateBadge();
      notify("Đã cập nhật hội thoại mẫu.");
    });
    return button;
  }));
  $("#actions-dialog").showModal();
}
$("#close-actions").addEventListener("click", () => $("#actions-dialog").close());
$("#chat-menu").addEventListener("click", () => { if (activeThread) openActions(activeThread); });
$("#chat-star").addEventListener("click", () => {
  if (!activeThread) return;
  activeThread.trusted = !activeThread.trusted;
  renderChat();
  notify(activeThread.trusted ? "Đã thêm vào ưu tiên." : "Đã bỏ ưu tiên.");
});
function openChat(path) {
  history.pushState({from:route}, "", `#${path}`);
  navigate();
}
$("#chat-back").addEventListener("click", () => {
  if (history.state?.from) history.back();
  else location.replace(`#${returnRoute}`);
});
$("#compose").addEventListener("click", () => openChat("compose"));
$("#search").addEventListener("input", renderInbox);
$("#filter").addEventListener("change", renderInbox);
function resizeComposer() {
  const input = $("#message-input");
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 128)}px`;
}
$("#message-input").addEventListener("input", () => {
  if (activeThread) activeThread.draft = $("#message-input").value;
  else draft = $("#message-input").value;
  resizeComposer();
});
$("#recipient").addEventListener("input", () => { draftRecipient = $("#recipient").value; });
$("#message-form").addEventListener("submit", event => {
  event.preventDefault();
  const body = $("#message-input").value.trim();
  const recipient = $("#recipient");
  recipient.setCustomValidity("");
  if (!body) return;
  if (!activeThread && !recipient.value.trim()) { recipient.setCustomValidity("Nhập người nhận mẫu."); recipient.reportValidity(); return; }
  if (!activeThread) {
    if (threads.length >= 100) { notify("Hộp thư demo đã đủ 100 hội thoại. Khôi phục trong Cài đặt."); return; }
    activeThread = {id:crypto.randomUUID(),name:recipient.value.trim(),unread:0,messages:[]};
    threads.unshift(activeThread);
    draft = "";
    draftRecipient = "";
    history.replaceState(history.state, "", `#chat/${activeThread.id}/inbox`);
    route = "chat";
  }
  if (activeThread.messages.length >= 100) { notify("Hội thoại demo đã đủ 100 tin nhắn."); return; }
  activeThread.messages.push({body, outgoing:true, time:new Date().toLocaleTimeString("vi-VN",{hour:"2-digit",minute:"2-digit"})});
  activeThread.draft = "";
  renderChat();
});
$("#recipient").addEventListener("input", () => $("#recipient").setCustomValidity(""));
$("#reset-demo").addEventListener("click", () => { threads = seedThreads(); draft = ""; draftRecipient = ""; updateBadge(); notify("Đã khôi phục hộp thư mẫu."); });
window.addEventListener("hashchange", navigate);
const scheme = window.matchMedia("(prefers-color-scheme: dark)");
function applyTheme() {
  const theme = $("#theme").value;
  document.documentElement.dataset.theme = theme === "system" ? scheme.matches ? "dark" : "light" : theme;
}
try { const saved = localStorage.getItem("o1brain-theme"); if (["system","light","dark"].includes(saved)) $("#theme").value = saved; } catch {}
$("#theme").addEventListener("change", () => { applyTheme(); try { localStorage.setItem("o1brain-theme", $("#theme").value); } catch {} });
scheme.addEventListener("change", applyTheme);
applyTheme();
const form = $("#sms-form");
const result = $("#result");
const text = $("#sms");
let busy = false;
function clearResult() {
  result.replaceChildren();
  $("#char-count").textContent = `${text.value.length} / 2.000`;
}
async function jsonResponse(response) {
  const data = await response.json();
  if (!response.ok) throw new Error(response.status === 429 ? "Đã đạt giới hạn yêu cầu. Vui lòng thử lại sau." : data.error?.message || "Không thể xử lý yêu cầu. Vui lòng thử lại.");
  return data;
}
form.addEventListener("submit", async event => {
  event.preventDefault();
  if (busy || !text.value.trim()) return;
  busy = true;
  for (const control of form.elements) control.disabled = true;
  result.setAttribute("aria-busy", "true");
  const skeleton = node("div", "", "skeleton");
  skeleton.setAttribute("role", "status");
  skeleton.append(node("span", "Đang kiểm tra tin nhắn…", "sr-only"));
  result.replaceChildren(skeleton);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);
  try {
    const data = await fetch("/api/v1/demo/compare", {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({text:text.value, language_hint:"en"}), signal:controller.signal}).then(jsonResponse);
    result.replaceChildren();
    for (const prediction of [...data.comparisons].reverse()) {
      const spam = prediction.policy ? prediction.policy.status === "suspected_spam" : prediction.classification.label === "spam";
      const block = node("article", "", `prediction${spam ? " spam" : ""}`);
      const heading = node("div", "", "prediction-heading");
      heading.append(icon("shield"), node("h2", spam ? "Có dấu hiệu tin nhắn rác" : "Mô hình phân loại: Ham"));
      const baseline = prediction.model.id === "english_baseline";
      block.append(heading, paragraph(baseline ? "Baseline gốc / Word TF-IDF (1–2) + Multinomial Naive Bayes" : "Character TF-IDF (3–5) + Linear SVM"), paragraph(`Nhãn model: ${prediction.classification.label.toUpperCase()} / Điểm: ${prediction.classification.score.toFixed(4)} / ${prediction.latency_ms.toFixed(2)} ms`));
      const details = node("details");
      details.append(node("summary", "Chi tiết mô hình"), paragraph(`Phiên bản: ${prediction.model.version}`), paragraph(prediction.classification.score_kind === "decision_margin" ? "Điểm là khoảng cách đến ranh giới quyết định. Điểm dương: Spam; điểm không dương: Ham. Không phải xác suất." : "Điểm chưa hiệu chỉnh, không phải xác suất."));
      block.append(details);
      result.append(block);
    }
  } catch (error) {
    result.replaceChildren(paragraph(error.name === "AbortError" ? "Máy chủ phản hồi quá lâu. Vui lòng thử lại." : error.message, "error"));
  } finally {
    clearTimeout(timeout);
    busy = false;
    result.setAttribute("aria-busy", "false");
    for (const control of form.elements) control.disabled = false;
  }
});
function sample(spam) {
  if (busy) return;
  const names = ["Alex", "Sarah", "James", "Emma", "Daniel", "Olivia"];
  const name = names[Math.floor(Math.random() * names.length)];
  const reference = crypto.randomUUID().slice(0, 8);
  const hour = 1 + Math.floor(Math.random() * 12);
  const amount = 10 + Math.floor(Math.random() * 990);
  const choices = spam ? [
    `Congratulations ${name}! You won $${amount}! Reply CLAIM with reference ${reference} to collect your prize.`,
    `FREE entry! Text WIN ${reference} now for a chance to win $${amount}. Limited offer!`,
    `Exclusive offer for ${name}! Get ${10 + Math.floor(Math.random() * 80)}% OFF today. Reply YES ${reference} for details.`
  ] : [
    `Hey ${name}, can we meet outside the library at ${hour}:30 tomorrow? I'll bring my notes.`,
    `Your verification code is ${100000 + Math.floor(Math.random() * 900000)}. It expires in 5 minutes. Do not share this code.`,
    `Hi ${name}, your order #${reference} has shipped. You can check its status in your account.`,
    `Reminder: Your appointment is tomorrow at ${hour}:00. Please arrive 10 minutes early. Reference ${reference}.`
  ];
  text.value = choices[Math.floor(Math.random() * choices.length)];
  clearResult();
  text.focus();
}
$("#sample").addEventListener("click", () => sample(false));
$("#sample-spam").addEventListener("click", () => sample(true));
form.addEventListener("reset", () => { result.replaceChildren(); $("#char-count").textContent = "0 / 2.000"; });
text.addEventListener("input", clearResult);
async function evidence() {
  try {
    const data = await fetch("/api/v1/demo/metrics").then(jsonResponse);
    const names = {english_baseline:"Word TF-IDF + Multinomial Naive Bayes (baseline gốc)", english_tuned_nb:"Word TF-IDF + Multinomial NB (sau tối ưu)", english_complement_nb:"Word TF-IDF + Complement NB", english_word_svm:"Word TF-IDF + Linear SVM", english_logreg:"Word TF-IDF + Logistic Regression", english_char_svm:"Character TF-IDF + Linear SVM (UCI)"};
    $("#metrics").replaceChildren(...Object.entries(data.reports).map(([name, report]) => {
      const row = node("tr");
      const c = report.confusion;
      const accuracy = (c.tn + c.tp) / (c.tn + c.fp + c.fn + c.tp);
      for (const value of [names[name] || name, report.metrics.spam_f1, report.metrics.spam_precision, report.metrics.spam_recall, accuracy, report.metrics.ham_fpr, `${c.tn} / ${c.fp} / ${c.fn} / ${c.tp}`]) row.append(node("td", value == null ? "Chưa có" : typeof value === "number" ? value.toFixed(5) : value));
      return row;
    }));
  } catch (error) {
    const row = node("tr");
    const cell = node("td", `${error.message} Tải lại trang để thử lại.`);
    cell.colSpan = 7;
    row.append(cell);
    $("#metrics").replaceChildren(row);
  }
  try {
    const model = await fetch("/api/v1/models/active").then(jsonResponse);
    $("#model-info").textContent = `${model.id} / ${model.version} / SHA256 ${model.artifact_sha256_prefix}`;
  } catch { $("#model-info").textContent = "Chưa tải được phiên bản mô hình. Vui lòng tải lại trang."; }
}
navigate();
evidence();
