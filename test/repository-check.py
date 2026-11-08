#!/usr/bin/env python3
"""Repository-local checks that require neither the Android SDK nor network access."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
res = ROOT / 'app/src/main/res'

# All Android XML must be well-formed.
for file in (ROOT / 'app/src/main').rglob('*.xml'):
    ET.parse(file)

# Resource names and printf placeholders must match across the translations.
def load_strings(folder):
    doc = ET.parse(res / folder / 'strings.xml')
    return {e.attrib['name']: e.text or '' for e in doc.getroot().findall('string')}

en = load_strings('values')
ja = load_strings('values-ja')
assert en.keys() == ja.keys(), f'Localization key mismatch: {en.keys() ^ ja.keys()}'
placeholders = re.compile(r'%\d+\$[ds]')
for key in en:
    assert sorted(placeholders.findall(en[key])) == sorted(placeholders.findall(ja[key])), key

# Every R.string reference must exist.
refs = set()
for src in (ROOT / 'app/src/main/java').rglob('*.kt'):
    refs |= set(re.findall(r'R\.string\.(\w+)', src.read_text()))
assert refs <= en.keys(), f'Missing Android strings: {refs - en.keys()}'

# Previous Android Studio compiler and Gradle wrapper fixes must remain applied.
main = (ROOT / 'app/src/main/java/jp/tsakailab/powerlogger/MainActivity.kt').read_text()
store = (ROOT / 'app/src/main/java/jp/tsakailab/powerlogger/PowerStore.kt').read_text()
build = (ROOT / 'app/build.gradle.kts').read_text()
wrapper = (ROOT / 'install-gradle-wrapper.ps1').read_text()
assert 'isFillViewport = true' in main
assert 'arrayOf<Any>(duration, event.id)' in store
assert 'kotlinOptions' not in build and 'compilerOptions' in build
assert 'v8.13.0' in wrapper
assert '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f' in wrapper
assert 'android:localeConfig="@xml/locale_config"' in (ROOT / 'app/src/main/AndroidManifest.xml').read_text()

# The public GAS source must use only placeholders and must still implement the key-preserving allowlist.
gas = (ROOT / 'server/Code.gs').read_text()
assert "'REPLACE_EMAIL_1@example.com'" in gas
assert "'REPLACE_EMAIL_2@example.com'" in gas
assert 'updateAllowedRecipients' in gas and 'ALLOWED_RECIPIENTS' in gas
assert 'MailApp.sendEmail({to: req.recipient' in gas
assert 'const EMAIL_LANGUAGE' in gas
print(f'PASS: XML, {len(en)} translations in both languages, Kotlin resources and prior build fixes')
