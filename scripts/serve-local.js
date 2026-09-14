// Local map server: injects GOOGLE_MAPS_API_KEY from .env into pages/index.html
// without writing the key into the repo.
const http = require("http");
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

http.createServer((req, res) => {
  let urlPath = decodeURIComponent((req.url || "/").split("?")[0]);
  if (urlPath === "/") urlPath = "/index.html";
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
      data = Buffer.from(String(data).replace(
        'const GOOGLE_MAPS_API_KEY = "__GOOGLE_MAPS_API_KEY__";',
        'const GOOGLE_MAPS_API_KEY = ' + JSON.stringify(mapsKey) + ";"
      ));
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
