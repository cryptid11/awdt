// Shows what the phone shares (files.json), with download links.
"use strict";

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

function render(share) {
  const count = share.files.length;
  document.title = "WDT · " + (count === 1 ? share.files[0].name : count + " files");
  document.getElementById("title").textContent =
    count === 1 ? "1 file" : count + " files";
  document.getElementById("subtitle").textContent =
    formatSize(share.total) + " from " + share.device;

  const list = document.getElementById("files");
  list.replaceChildren();
  for (const file of share.files) {
    const item = document.createElement("li");

    const badge = document.createElement("span");
    badge.className = "badge";
    badge.textContent = extension(file.name);

    const info = document.createElement("div");
    info.className = "info";
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = file.name;
    const size = document.createElement("span");
    size.className = "muted";
    size.textContent = formatSize(file.size);
    info.append(name, size);

    const link = document.createElement("a");
    link.className = "button" + (count === 1 ? " primary" : "");
    link.href = file.url;
    link.download = file.name;
    link.textContent = "Download";
    link.setAttribute("aria-label", "Download " + file.name);

    item.append(badge, info, link);
    list.append(item);
  }

  if (share.zip) {
    const all = document.getElementById("all");
    all.href = share.zip;
    all.download = "WDT files.zip";
    all.textContent = "Download all (" + formatSize(share.total) + ", zip)";
    all.hidden = false;
  }
  // Phones that have the app can get the files faster through WDT
  if (/Android/i.test(navigator.userAgent) && share.appLink) {
    const app = document.getElementById("app");
    app.href = share.appLink;
    app.hidden = false;
  }
}

fetch("files.json", { cache: "no-store" })
  .then((response) => {
    if (!response.ok) throw new Error(response.status);
    return response.json();
  })
  .then(render)
  .catch(() => {
    document.getElementById("subtitle").hidden = true;
    document.getElementById("error").hidden = false;
  });
