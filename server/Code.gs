/**
 * PowerLogger notification endpoint (Google Apps Script).
 * ALLOWED_EMAILS: addresses that the Android app may select as recipients.
 * An event is sent to ONE requested address if and only if it is on this list.
 * Existing users: replace Code.gs, edit ALLOWED_EMAILS, run updateAllowedRecipients(),
 * then update the existing web-app deployment to a new version.
 * New users: edit ALLOWED_EMAILS and run setup() once to generate the shared secret.
 */
const ALLOWED_EMAILS = [
  'REPLACE_EMAIL_1@example.com',
  'REPLACE_EMAIL_2@example.com'
];

// Language used for outgoing email (the Android UI language is set separately).
// Valid values: 'ja' or 'en'. Leave as 'ja' when upgrading an existing deployment.
const EMAIL_LANGUAGE = 'ja';
const EMAIL_TIME_ZONE = 'Asia/Tokyo';

function normalizedConfiguredRecipients_() {
  if (!Array.isArray(ALLOWED_EMAILS) || ALLOWED_EMAILS.length === 0) {
    throw new Error('ALLOWED_EMAILS must contain at least one address.');
  }
  const emails = ALLOWED_EMAILS.map(function(email) {
    if (typeof email !== 'string') throw new Error('Each recipient must be a string.');
    return email.trim().toLowerCase();
  });
  if (emails.some(function(email) {
    return email.indexOf('REPLACE_EMAIL') !== -1 ||
      !/^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]+$/.test(email);
  })) {
    throw new Error('Replace the example email addresses in ALLOWED_EMAILS.');
  }
  if (new Set(emails).size !== emails.length) {
    throw new Error('ALLOWED_EMAILS contains duplicate addresses.');
  }
  return emails;
}

/** Run only on first installation; does not overwrite an existing shared secret. */
function setup() {
  const recipients = normalizedConfiguredRecipients_();
  const props = PropertiesService.getScriptProperties();
  if (props.getProperty('SHARED_SECRET')) {
    throw new Error('Already initialized. Run updateAllowedRecipients() instead.');
  }
  const secret = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
  props.setProperties({
    SHARED_SECRET: secret,
    ALLOWED_RECIPIENTS: JSON.stringify(recipients),
    ALLOWED_RECIPIENT: recipients[0] // for safe rollback to the original single-recipient server
  });
  console.log('COPY THIS SECRET TO THE ANDROID APP: ' + secret);
  console.log('ALLOWED_RECIPIENTS: ' + recipients.join(', '));
}

/** Change approved recipients WITHOUT changing the existing Android shared secret. */
function updateAllowedRecipients() {
  const recipients = normalizedConfiguredRecipients_();
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty('SHARED_SECRET')) {
    throw new Error('Not initialized. Run setup() first.');
  }
  props.setProperties({
    ALLOWED_RECIPIENTS: JSON.stringify(recipients),
    ALLOWED_RECIPIENT: recipients[0] // old deployed version can still send to an approved address
  });
  console.log('ALLOWED_RECIPIENTS updated: ' + recipients.join(', '));
  console.log('SHARED_SECRET unchanged.');
}

function allowedRecipients_(props) {
  const json = props.getProperty('ALLOWED_RECIPIENTS');
  if (json) {
    const recipients = JSON.parse(json);
    return Array.isArray(recipients) ? recipients : [];
  }
  // Keep the old one-address deployment functional until updateAllowedRecipients() runs.
  const old = props.getProperty('ALLOWED_RECIPIENT');
  return old ? [old] : [];
}

/** Run once to grant MailApp permission before deploying the web app. */
function authorizeMail() {
  console.log('Remaining daily recipients: ' + MailApp.getRemainingDailyQuota());
}

function json_(object) {
  return ContentService.createTextOutput(JSON.stringify(object))
    .setMimeType(ContentService.MimeType.JSON);
}

function hmac_(message, secret) {
  return Utilities.computeHmacSha256Signature(message, secret)
    .map(function(b) { return ('0' + ((b + 256) % 256).toString(16)).slice(-2); })
    .join('');
}

function equal_(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function doPost(e) {
  try {
    const p = PropertiesService.getScriptProperties();
    const secret = p.getProperty('SHARED_SECRET');
    const recipients = allowedRecipients_(p);
    if (!secret || !recipients.length) return json_({ok:false,error:'server_not_configured'});
    const req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (req.version !== 1 || !/^[0-9a-f-]{36}$/i.test(String(req.id || '')) ||
        ['DOWN','UP','TEST'].indexOf(req.type) < 0 ||
        !Number.isSafeInteger(req.timestamp) ||
        !['BROADCAST','POLL','STARTUP','BOOT','MANUAL'].includes(req.source)) {
      return json_({ok:false,error:'invalid_payload'});
    }
    if (typeof req.recipient !== 'string' || !recipients.includes(req.recipient)) {
      return json_({ok:false,error:'recipient_not_allowed'});
    }
    const duration = req.durationMs === undefined ? '' : String(req.durationMs);
    if (duration !== '' && (!Number.isSafeInteger(req.durationMs) || req.durationMs < 0)) {
      return json_({ok:false,error:'invalid_duration'});
    }
    const canonical = '1|' + req.id + '|' + req.type + '|' + req.timestamp + '|' +
        req.recipient + '|' + req.source + '|' + duration;
    if (!equal_(req.signature, hmac_(canonical, secret))) {
      return json_({ok:false,error:'invalid_signature'});
    }
    const lock = LockService.getScriptLock();
    if (!lock.tryLock(15000)) return json_({ok:false,error:'busy_retry'});
    try {
      const eventKey = 'sent_' + req.id;
      if (p.getProperty(eventKey)) return json_({ok:true,status:'duplicate'});
      const date = Utilities.formatDate(new Date(req.timestamp), EMAIL_TIME_ZONE,
          'yyyy/MM/dd HH:mm:ss');
      const message = emailMessage_(req, date, duration);
      // Only the requested, allowlisted recipient is notified (never the entire list).
      MailApp.sendEmail({to: req.recipient, subject: message.subject,
        body: message.body, name: message.sender});
      // Durable deduplication: retries of the same ID should not send twice.
      p.setProperty(eventKey, String(Date.now()));
      return json_({ok:true,status:'sent'});
    } finally { lock.releaseLock(); }
  } catch (err) {
    console.error(err);
    return json_({ok:false,error:'server_error_check_apps_script_logs'});
  }
}

function emailMessage_(req, date, duration) {
  const en = EMAIL_LANGUAGE === 'en';
  const subjects = en ? {
    DOWN: '[Power Logger] External USB power disconnected',
    UP: '[Power Logger] External USB power restored',
    TEST: '[Power Logger] Test email'
  } : {
    DOWN: '【停電ロガー】外部給電の停止を検出',
    UP: '【停電ロガー】外部給電の復旧を検出',
    TEST: '【停電ロガー】テスト通知'
  };
  const typeText = en ? {
    DOWN: 'External USB power was disconnected.',
    UP: 'External USB power was restored.',
    TEST: 'This is a test notification.'
  } : {
    DOWN: '外部給電が停止しました',
    UP: '外部給電が復旧しました',
    TEST: 'テスト通知です'
  };
  const detected = en ?
      (req.source === 'BROADCAST' ? 'Power connection event' : 'Later state check; event time may be delayed') :
      (req.source === 'BROADCAST' ? '給電イベントから検知' : '後から状態を確認（実際の発生時刻は前後する可能性あり）');
  const durationText = duration === '' ? (en ? 'Unknown' : '(計測不能)') :
      durationHuman_(Number(duration), en);
  const lines = en ? [
    typeText[req.type],
    'Detected at (' + EMAIL_TIME_ZONE + '): ' + date,
    'Detection method: ' + detected,
    ...(req.type === 'UP' ? ['Recorded outage duration: ' + durationText] : []),
    'Event ID: ' + req.id,
    '',
    'Automatically sent by Power Logger for Android.',
    'The app monitors USB charging power, not the AC mains directly.'
  ] : [
    typeText[req.type],
    '検知日時（' + EMAIL_TIME_ZONE + '）: ' + date,
    '検知方式: ' + detected,
    ...(req.type === 'UP' ? ['記録された停電時間: ' + durationText] : []),
    'イベントID: ' + req.id,
    '',
    'このメールはPowerLogger Androidから自動送信されました。',
    '実際の商用電源停電ではなくUSB給電停止を検知しています。'
  ];
  return {subject:subjects[req.type], body:lines.join('\n'), sender:en ? 'Power Logger' : '停電ロガー'};
}

function durationHuman_(millis, en) {
  const seconds = Math.floor(millis / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return en ? (hours + 'h ' + minutes + 'm ' + (seconds % 60) + 's') :
      (hours + '時間' + minutes + '分' + (seconds % 60) + '秒');
}

/** Optional maintenance: schedule monthly via the Apps Script Triggers UI. */
function deleteOldDedupEntries() {
  const props = PropertiesService.getScriptProperties();
  const all = props.getProperties();
  const threshold = Date.now() - 90 * 24 * 3600 * 1000;
  Object.keys(all).forEach(function(key) {
    if (key.startsWith('sent_') && Number(all[key]) < threshold) props.deleteProperty(key);
  });
}

function doGet() {
  return json_({ok:false,error:'POST_only'});
}
