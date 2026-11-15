import { build } from 'esbuild';
import { cpSync, existsSync, mkdirSync } from 'node:fs';

if (existsSync('.env')) process.loadEnvFile('.env');
const env = (k) => process.env[k] ?? '';

const firebaseConfig = {
  apiKey: env('FIREBASE_API_KEY'),
  authDomain: env('FIREBASE_AUTH_DOMAIN'),
  projectId: env('FIREBASE_PROJECT_ID'),
  storageBucket: env('FIREBASE_STORAGE_BUCKET'),
  messagingSenderId: env('FIREBASE_MESSAGING_SENDER_ID'),
  appId: env('FIREBASE_APP_ID'),
};
if (!firebaseConfig.apiKey) console.warn('warning: .env missing - cloud features will fail (see .env.example)');

mkdirSync('dist', { recursive: true });
cpSync('static', 'dist', { recursive: true });

await build({
  entryPoints: ['src/panel.js', 'src/content.js', 'src/background.js'],
  bundle: true,
  format: 'esm',
  target: 'chrome139',
  outdir: 'dist',
  define: {
    __FIREBASE_CONFIG__: JSON.stringify(firebaseConfig),
    __APPCHECK_DEBUG_TOKEN__: JSON.stringify(env('APPCHECK_DEBUG_TOKEN')),
  },
});
console.log('built dist/ - load it at chrome://extensions (Developer mode > Load unpacked)');
