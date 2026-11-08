// Local-only verification of Apps Script logic; no emails are actually sent.
const fs = require('fs');
const vm = require('vm');
const crypto = require('crypto');
const assert = require('assert');
const props = new Map();
const sent = [];
const context = {
  console, Date, JSON, Number, String, Object, RegExp, Set,
  Utilities: {
    getUuid: () => crypto.randomUUID(),
    computeHmacSha256Signature: (m, s) => [...crypto.createHmac('sha256', s).update(m).digest()]
      .map(v => v > 127 ? v - 256 : v),
    formatDate: d => d.toISOString()
  },
  PropertiesService: { getScriptProperties: () => ({
    getProperty: k => props.get(k), setProperty: (k,v) => props.set(k,v),
    setProperties: entries => Object.entries(entries).forEach(([k,v]) => props.set(k,v)),
    getProperties: () => Object.fromEntries(props), deleteProperty: k => props.delete(k)
  }) },
  ContentService: { MimeType: { JSON: 'JSON' }, createTextOutput: s => ({ text:s, setMimeType() { return this; }}) },
  LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock() {} }) },
  MailApp: { sendEmail: mail => sent.push(mail), getRemainingDailyQuota: () => 100 }
};
vm.createContext(context);
const code = fs.readFileSync(__dirname+'/../server/Code.gs','utf8')
  .replace('REPLACE_EMAIL_1@example.com', 'one@example.com')
  .replace('REPLACE_EMAIL_2@example.com', 'two@example.com');
vm.runInContext(code,context);
context.setup();
const secret = props.get('SHARED_SECRET');
assert.equal(secret.length,64);
assert.deepEqual(JSON.parse(props.get('ALLOWED_RECIPIENTS')),['one@example.com','two@example.com']);
function sign(e) {
  const duration = e.durationMs === undefined ? '' : String(e.durationMs);
  const canonical = `1|${e.id}|${e.type}|${e.timestamp}|${e.recipient}|${e.source}|${duration}`;
  return { ...e, signature: crypto.createHmac('sha256',secret).update(canonical).digest('hex') };
}
function post(e) { return JSON.parse(context.doPost({postData:{contents:JSON.stringify(e)}}).text); }
function event(recipient, type='DOWN', millis=1793950000000) {
  return sign({version:1,id:crypto.randomUUID(),type,timestamp:millis,
    recipient,source:'BROADCAST'});
}
const first = event('one@example.com');
assert.equal(post(first).status,'sent');
assert.equal(post(first).status,'duplicate');
assert.equal(sent.length,1);
assert.equal(sent[0].to,'one@example.com');
assert.equal(post(event('two@example.com')).status,'sent');
assert.equal(sent[1].to,'two@example.com');
assert.equal(post(event('third@example.com')).error,'recipient_not_allowed');
const tampered = {...first,id:crypto.randomUUID()};
assert.equal(post(tampered).error,'invalid_signature');
const up = sign({...first,id:crypto.randomUUID(),type:'UP',durationMs:61000});
assert.equal(post(up).status,'sent');
assert.match(sent.at(-1).body,/記録された停電時間: 0時間1分1秒/);
// Migrate existing install, preserving secret: update to ONLY second address.
const patchedCode = code.replace("'one@example.com',\n  'two@example.com'", "'two@example.com'");
const updateCtx = {...context};
vm.createContext(updateCtx);vm.runInContext(patchedCode, updateCtx);
updateCtx.updateAllowedRecipients();
assert.equal(props.get('SHARED_SECRET'),secret);
assert.deepEqual(JSON.parse(props.get('ALLOWED_RECIPIENTS')),['two@example.com']);
assert.equal(post(event('one@example.com')).error,'recipient_not_allowed');
assert.equal(post(event('two@example.com')).status,'sent');
assert.equal(sent.at(-1).to,'two@example.com');
console.log('PASS: HMAC, 2 allowed addresses, 1 chosen per event, disallowed address, duplicate, UP, key-preserving update');

// The email locale is configurable on the server without changing the v1 HMAC protocol.
// The same user's Android app and shared key work after switching to English.
const englishContext = {...context};
vm.createContext(englishContext);
vm.runInContext(code.replace("const EMAIL_LANGUAGE = 'ja';", "const EMAIL_LANGUAGE = 'en';"), englishContext);
const englishUp = sign({...event('two@example.com'), id:crypto.randomUUID(),
  type:'UP',durationMs:125000});
const englishResult = JSON.parse(englishContext.doPost({postData:{contents:JSON.stringify(englishUp)}}).text);
assert.equal(englishResult.status, 'sent');
assert.equal(sent.at(-1).to, 'two@example.com');
assert.match(sent.at(-1).subject, /External USB power restored/);
assert.match(sent.at(-1).body, /Recorded outage duration: 0h 2m 5s/);
console.log('PASS: English email template with unchanged HMAC format and recipient allowlist');
