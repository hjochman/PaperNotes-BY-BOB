#!/bin/bash
# usage: ./tools/ui.sh <name>   -> dumps current UI hierarchy + screenshot into tmp/device/ui/
set -e
S=${ANDROID_SERIAL:-$(adb get-serialno)}
N=${1:-ui}
mkdir -p "$(dirname "$0")/../tmp/device/ui"
adb -s $S shell "uiautomator dump /sdcard/$N.xml >/dev/null 2>&1; screencap -p /sdcard/$N.png"
adb -s $S pull /sdcard/$N.xml "$(dirname "$0")/../tmp/device/ui/" >/dev/null
adb -s $S pull /sdcard/$N.png "$(dirname "$0")/../tmp/device/ui/" >/dev/null
adb -s $S shell "rm /sdcard/$N.xml /sdcard/$N.png"
python3 - "$(dirname "$0")/../tmp/device/ui/$N.xml" <<'E'
import re,sys
d=open(sys.argv[1],encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',d):
    n=m.group(0)
    g=lambda a:(re.search(r' %s="([^"]*)"'%a,n) or [0,''])[1]
    if g('text') or g('content-desc') or g('resource-id'):
        print(g('class').split('.')[-1],'|',g('resource-id').split('/')[-1],'|',g('text'),'|',g('content-desc'),'|',g('bounds'),'| click' if g('clickable')=='true' else '')
E
