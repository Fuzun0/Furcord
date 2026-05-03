const https = require('https');

const projectId = process.env.FIREBASE_PROJECT_ID;
const version = process.env.APP_VERSION;
const downloadUrl = process.env.DOWNLOAD_URL;
const bearerToken = process.env.FIRESTORE_BEARER_TOKEN;
const releaseNotes = process.env.RELEASE_NOTES || `Yeni surum: ${version}`;

if (!projectId || !version || !downloadUrl || !bearerToken) {
  console.error('Eksik env. Gerekli: FIREBASE_PROJECT_ID, APP_VERSION, DOWNLOAD_URL, FIRESTORE_BEARER_TOKEN');
  process.exit(1);
}

const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/config/appVersion`;
const body = JSON.stringify({
  fields: {
    latestVersion: { stringValue: version },
    downloadUrl: { stringValue: downloadUrl },
    releaseNotes: { stringValue: releaseNotes },
  },
});

const req = https.request(
  url,
  {
    method: 'PATCH',
    headers: {
      Authorization: `Bearer ${bearerToken}`,
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
    },
  },
  (res) => {
    let data = '';
    res.on('data', (chunk) => (data += chunk));
    res.on('end', () => {
      if (res.statusCode >= 200 && res.statusCode < 300) {
        console.log(`Firestore guncellendi: ${version}`);
        process.exit(0);
      }
      console.error(`Firestore guncelleme basarisiz: HTTP ${res.statusCode}`);
      console.error(data.slice(0, 1000));
      process.exit(1);
    });
  }
);

req.on('error', (err) => {
  console.error(err.message);
  process.exit(1);
});

req.write(body);
req.end();
