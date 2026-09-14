// Local map server: injects GOOGLE_MAPS_API_KEY from .env into pages/index.html
// without writing the key into the repo.
const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const root = path.resolve(__dirname, "..");
const pages = path.join(root, "pages");
const port = Number(process.env.PORT) || 8877;

function loadDotEnv() {
  const file = path.join(root, ".env");
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith("#")) continue;
    const i = t.indexOf("=");
    if (i < 1) continue;
    const name = t.slice(0, i).trim();
    let value = t.slice(i + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!process.env[name]) process.env[name] = value;
  }
}

loadDotEnv();
const mapsKey = (process.env.GOOGLE_MAPS_API_KEY || "").trim();
const customMapId = (process.env.CUSTOM_MAP_ID || "").trim();
const customMapName = (process.env.CUSTOM_MAP_NAME || "").trim();
if (!mapsKey || !mapsKey.startsWith("AIza")) {
  console.error("Add GOOGLE_MAPS_API_KEY to .env (see .env.example).");
  process.exit(1);
}

const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".glb": "model/gltf-binary",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".json": "application/json"
};

function proxyCustomMapKml(res) {
  if (!customMapId) {
    res.writeHead(404, { "Cache-Control": "no-store" });
    res.end("custom map id not configured");
    return;
  }
  const url = "https://www.google.com/maps/d/kml?mid=" + encodeURIComponent(customMapId) + "&forcekml=1";
  https.get(url, { headers: { "User-Agent": "AABrowser-local" } }, up => {
    const status = up.statusCode || 502;
    if (status >= 400) {
      up.resume();
      res.writeHead(status, { "Cache-Control": "no-store" });
      res.end("custom map fetch failed");
      return;
    }
    res.writeHead(200, {
      "Content-Type": up.headers["content-type"] || "application/vnd.google-earth.kml+xml; charset=utf-8",
      "Cache-Control": "no-store"
    });
    up.pipe(res);
  }).on("error", () => {
    res.writeHead(502, { "Cache-Control": "no-store" });
    res.end("custom map fetch failed");
  });
}

http.createServer((req, res) => {
  let urlPath = decodeURIComponent((req.url || "/").split("?")[0]);
  if (urlPath === "/") urlPath = "/index.html";
  if (urlPath === "/custom-map.kml") {
    proxyCustomMapKml(res);
    return;
  }
  const file = path.normalize(path.join(pages, urlPath.replace(/^\/+/, "")));
  if (!file.startsWith(pages)) {
    res.writeHead(403);
    res.end();
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end("not found");
      return;
    }
    const ext = path.extname(file);
    if (ext === ".html") {
      let html = String(data);
      html = html.replace(
        'const GOOGLE_MAPS_API_KEY = "__GOOGLE_MAPS_API_KEY__";',
        "const GOOGLE_MAPS_API_KEY = " + JSON.stringify(mapsKey) + ";"
      );
      html = html.replace(
        'const CUSTOM_MAP_ID = "__CUSTOM_MAP_ID__";',
        "const CUSTOM_MAP_ID = " + JSON.stringify(customMapId) + ";"
      );
      html = html.replace(
        'const CUSTOM_MAP_NAME = "__CUSTOM_MAP_NAME__";',
        "const CUSTOM_MAP_NAME = " + JSON.stringify(customMapName) + ";"
      );
      data = Buffer.from(html);
    }
    res.writeHead(200, {
      "Content-Type": types[ext] || "application/octet-stream",
      "Cache-Control": "no-store"
    });
    res.end(data);
  });
}).listen(port, "127.0.0.1", () => {
  console.log("Local map at http://127.0.0.1:" + port + "/");
});
