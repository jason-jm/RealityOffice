#!/usr/bin/env node
// Rebuild the localized website guides after editing docs/languages/*.md.
// Requires the Marked CLI on PATH. Pass its executable path as the first argument if needed.
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const sourceDir = path.join(root, "docs/languages");
const site = "https://jason-jm.github.io/RealityOffice/";
const repo = "https://github.com/jason-jm/RealityOffice/blob/main/";
const langPattern = /\[([^\]]+)\]\((?:\.\.\/\.\.\/README\.md|([\w-]+)\.md)\)|\*\*([^*]+)\*\*/g;
const escape = (text) => text.replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
const guideLink = (lang) => lang === "en" ? "../../" : lang === "zh-CN" ? "../../zh-cn/" : "../" + lang.toLowerCase() + "/";
const executable = process.argv[2] || "marked";
for (const filename of readdirSync(sourceDir)) {
  if (!filename.endsWith(".md") || ["README.md", "zh-CN.md"].includes(filename)) continue;
  const lang = path.basename(filename, ".md");
  const source = readFileSync(path.join(sourceDir, filename), "utf8");
  const [titleBlock, languageBlock, websiteBlock, ...body] = source.trim().split(/\n\n/);
  const title = titleBlock.slice(2).trim();
  const currentName = languageBlock.match(/\*\*([^*]+)\*\*/)[1];
  const nav = Array.from(languageBlock.matchAll(langPattern), ([, label, other, active]) => {
    const item = active ? lang : other || "en";
    return `<a lang="${item}" hreflang="${item}" href="${guideLink(item)}"${active ? ' aria-current="page"' : ''}>${escape(active || label)}</a>`;
  }).join("\n        ");
  const film = Array.from(websiteBlock.matchAll(/\[([^\]]+)\]\(([^)]+)\)/g)).at(-1);
  if (!film || !film[2].endsWith("#film")) throw Error("Missing film navigation in " + filename);
  const raw = execFileSync(executable, [], { input: body.join("\n\n"), encoding: "utf8" });
  const rewritten = raw.replace(/\b(href|src|srcset)="([^"]+)"/g, (attribute, kind, target) => {
    if (target.startsWith("#") || /^[a-z]+:/i.test(target)) return attribute;
    const [targetPath, fragment] = target.split("#");
    const sourcePath = path.posix.normalize(path.posix.join("docs/languages", targetPath));
    const resolved = sourcePath.startsWith("docs/gallery/")
      ? "../../" + sourcePath.slice(5)
      : repo + sourcePath;
    return `${kind}="${resolved}${fragment ? "#" + fragment : ""}"`;
  }).replace(/<a name="([\w-]+)"><\/a>/g, '<a id="$1"></a>');
  const html = `<!doctype html>
<html lang="${lang}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="theme-color" content="#f8f8f5">
  <meta name="description" content="${escape(body[0].replace(/\[([^\]]+)\]\([^)]+\)/g, "$1").replace(/\*+/g, "").slice(0,245))}">
  <title>${escape(title)}</title>
  <link rel="canonical" href="${site}guides/${lang.toLowerCase()}/">
  <link rel="icon" href="../../site/icon.png" type="image/png">
  <link rel="stylesheet" href="../../site/office.css">
</head>
<body>
  <header class="topline"><div class="shell mast">
    <a class="brand" href="../../"><img src="../../site/icon.png" width="32" height="32" alt="">Reality Office</a>
    <nav class="nav-actions"><a href="${film[2]}">${escape(film[1])}</a><a class="quiet-link" href="https://github.com/jason-jm/RealityOffice">GitHub ↗</a></nav>
  </div></header>
  <main class="guide shell">
    <details class="guide-lang"><summary>${escape(currentName)}</summary>
      <nav>${nav}</nav>
    </details>
    <h1>${escape(title)}</h1>
    ${rewritten.trim()}
  </main>
</body>
</html>
`;
  const folder = path.join(root, "docs/guides", lang.toLowerCase());
  mkdirSync(folder, { recursive: true });
  writeFileSync(path.join(folder, "index.html"), html);
}
