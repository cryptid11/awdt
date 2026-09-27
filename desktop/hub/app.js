// Lists the devices the hub knows (devices.json, refreshed every few
// seconds) and, for the phones sharing files, their files: fetched from the
// phones themselves (their files.json), downloaded from them directly.
"use strict";

const fileLists = new Map(); // share URL -> {files, total, zip} or "error"
let lastRendered = "";

function formatSize(bytes) {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }
  return (unit === 0 ? value : value.toFixed(value < 10 ? 1 : 0)) + " " + units[unit];
}

function extension(name) {
  const dot = name.lastIndexOf(".");
  return dot > 0 && dot > name.length - 7 ? name.slice(dot + 1).toUpperCase() : "FILE";
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function loadFiles(share) {
  if (fileLists.has(share)) return;
  fileLists.set(share, null); // loading
  fetch(share + "files.json", { cache: "no-store" })
    .then((r) => {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    })
    .then((list) => fileLists.set(share, list))
    .catch(() => fileLists.set(share, "error"))
    .finally(() => refresh());
}

function phoneCard(device) {
  const card = el("article", "card");
  const head = el("div", "card-head");
  head.append(el("h2", "", device.name), el("span", "muted", device.address));
  card.append(head);

  const list = fileLists.get(device.share);
  if (!list) {
    card.append(el("p", "muted", "Loading the files…"));
    return card;
  }
  if (list === "error") {
    card.append(el("p", "error", "Can't reach this phone's share."));
    return card;
  }
  card.querySelector(".muted").textContent =
    device.address + " · " + list.files.length +
    (list.files.length === 1 ? " file, " : " files, ") + formatSize(list.total);

  const files = el("ul", "files");
  list.files.forEach((file) => {
    const item = el("li");
    const info = el("div", "info");
    info.append(el("span", "name", file.name), el("span", "muted", formatSize(file.size)));
    const link = el("a", "button", "Download");
    link.href = device.share + file.url;
    link.setAttribute("aria-label", "Download " + file.name);
    item.append(el("span", "badge", extension(file.name)), info, link);
    files.append(item);
  });
  card.append(files);

  if (list.zip) {
    const all = el("a", "button primary", "Download all (" + formatSize(list.total) + ", zip)");
    all.href = device.share + list.zip;
    const actions = el("div", "actions");
    actions.append(all);
    card.append(actions);
  }
  return card;
}

function otherCard(device) {
  const card = el("article", "card compact");
  const head = el("div", "card-head");
  head.append(el("h2", "", device.name), el("span", "muted", device.address));
  card.append(head);
  const what = device.kind === "computer"
    ? "Computer running awdt: send it files with the WDT app" +
      (device.autoAccept ? "." : " (it asks before accepting).")
    : "Phone with the WDT app, not sharing anything right now.";
  card.append(el("p", "muted", what));
  return card;
}

function render(data) {
  const sharing = data.devices.filter((d) => d.kind === "phone" && d.share);
  const others = data.devices.filter((d) => !(d.kind === "phone" && d.share));
  sharing.forEach((d) => loadFiles(d.share));
  // forget the lists of shares that are gone
  const live = new Set(sharing.map((d) => d.share));
  for (const share of fileLists.keys()) if (!live.has(share)) fileLists.delete(share);

  // only touch the page when something changed (keeps focus and hover)
  const state = JSON.stringify([data, [...fileLists]]);
  if (state === lastRendered) return;
  lastRendered = state;

  document.title = "WDT hub · " + data.hub;
  document.getElementById("subtitle").textContent =
    "Seen by " + data.hub + " · " + data.devices.length +
    (data.devices.length === 1 ? " device" : " devices");

  const sharingSection = document.getElementById("sharing");
  sharingSection.replaceChildren();
  if (sharing.length) {
    sharingSection.append(el("h3", "section", "Sharing files"));
    sharing.forEach((d) => sharingSection.append(phoneCard(d)));
  }
  const othersSection = document.getElementById("others");
  othersSection.replaceChildren();
  if (others.length) {
    othersSection.append(el("h3", "section", "Other devices"));
    others.forEach((d) => othersSection.append(otherCard(d)));
  }
  document.getElementById("empty").hidden = data.devices.length > 0;
}

function refresh() {
  fetch("devices.json", { cache: "no-store" })
    .then((r) => r.json())
    .then((data) => {
      document.getElementById("offline").hidden = true;
      render(data);
    })
    .catch(() => {
      document.getElementById("offline").hidden = false;
    });
}

refresh();
setInterval(refresh, 3000);
// file lists can change while a phone shares: reload them now and then
setInterval(() => {
  fileLists.clear();
  refresh();
}, 20000);
