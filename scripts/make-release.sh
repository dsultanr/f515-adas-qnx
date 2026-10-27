#!/usr/bin/env bash
# Local metadata only. No server credentials or automatic deployment.
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
: "${RELEASE_BASE_URL:?Set the HTTPS URL of your own release directory}"
"$base/build.sh"
python3 - "$base" "$RELEASE_BASE_URL" <<'PY'
import hashlib, json, pathlib, sys, xml.etree.ElementTree as ET
from urllib.parse import urlsplit
base = pathlib.Path(sys.argv[1]); url = sys.argv[2].rstrip('/')
u = urlsplit(url)
assert u.scheme == 'https' and u.hostname and not (u.username or u.password or u.query or u.fragment)
a = '{http://schemas.android.com/apk/res/android}'
m = ET.parse(base/'app/AndroidManifest.xml').getroot()
version = m.attrib[a+'versionName']; apk = base/'build'/('F515AdasQnx-'+version+'.apk')
j = dict(version=version, version_code=int(m.attrib[a+'versionCode']), tag_name='v'+version,
         name='F515 ADAS QNX '+version, body=(base/'RELEASE_NOTES.md').read_text(),
         apk_url=url+'/'+apk.name, size=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
(base/'build/latest.json').write_text(json.dumps(j, ensure_ascii=False, indent=2)+'\n')
print(base/'build/latest.json')
PY
