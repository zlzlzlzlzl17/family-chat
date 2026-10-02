const fs = require("fs");
const path = require("path");
const vm = require("vm");

const htmlPath = path.resolve(
  process.argv[2] || path.join(__dirname, "..", "manage_public", "index.html")
);
const html = fs.readFileSync(htmlPath, "utf8");
const match = html.match(/<script>([\s\S]*?)<\/script>/i);

if (!match) {
  throw new Error("manage_script_not_found");
}

new vm.Script(match[1], { filename: htmlPath });
console.log("manage script syntax ok");
