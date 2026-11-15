// Builds .harness/: the built panel plus chrome.* stubs, for smoke-testing on localhost.
import { cpSync, readFileSync, writeFileSync } from 'node:fs';
cpSync('dist', '.harness', { recursive: true });
cpSync('dev/stub.js', '.harness/stub.js');
const html = readFileSync('dist/panel.html', 'utf8').replace('<script type="module"', '<script src="stub.js"></script>\n  <script type="module"');
writeFileSync('.harness/index.html', html);
