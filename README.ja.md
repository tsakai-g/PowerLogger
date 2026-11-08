# 停電ロガー / Power Logger for Android

[English README](README.md) · [変更履歴](CHANGELOG.md) · [セキュリティ](SECURITY.md) · [MIT License](LICENSE)

Androidスマホを、**USB給電の停止・復旧を記録する装置**として利用するアプリです。

> **重要：** 商用電源100Vを直接測定する装置ではありません。USB電源アダプターの出力が切れたことを検出します。ケーブルを抜く、アダプターが故障するなどの場合も同じイベントになります。安全・防災用の保証付き警報装置ではありません。
>
> **検証状況：** 以前の日本語試作版は、実際にAndroid Studioでビルドが成功しています。この統合版v0.2.0についてはソース検査と模擬テストを実施していますが、この環境では**Android SDKが使えないためAPKの再ビルドとAndroid端末での実機試験は未実施**です。GitHub公開後にActionsでビルドを自動検証できる設定を同梱しています。

## 実装済みの機能

- USB給電停止（停電とみなす）と復旧を検知し、開始・復旧時刻と継続時間を記録。
- SQLiteでの履歴保存、履歴一覧、Excelで開けるUTF-8 CSV出力。
- 常駐通知を伴う監視サービス、Android電源接続イベントと20秒ごとの状態再確認。
- 再起動後の監視自動復帰を試みる（Androidや端末メーカーの制限を受ける）。
- メール通知は**初期状態OFF**。ONにすると停電、復電、テストのメールを送信。
- LTEが利用できない間は端末内に送信待ちとして保存し、通信回復後に再試行（即時送信は保証しない）。
- Google Apps Scriptには**複数の許可アドレス**を登録できる。ただし**アプリで指定する宛先は1件のみ**で、その1件が許可されている場合にのみ送信。
- HMAC-SHA256認証とイベントIDによる重複送信の抑制。Gmailのパスワードはアプリに保存しない。
- UI、通知、CSVは日本語・英語。GASから送るメールの言語は別途設定可能。
- Google Playへ公開せず、APKを直接インストールできる。

## ファイル構成

```text
PowerLoggerAndroid/
├── app/src/main/java/jp/tsakailab/powerlogger/   # Kotlinアプリ
├── app/src/main/res/values/strings.xml           # 英語（既定値）
├── app/src/main/res/values-ja/strings.xml        # 日本語
├── app/src/main/res/xml/locale_config.xml      # アプリ別言語切替
├── server/Code.gs                                 # GAS送信側（公開用）
├── test/server-tests.js                          # 実際のメールを送らないテスト
├── .github/workflows/android-build.yml          # GitHubでビルド検証
├── SETUP-FIRST.bat / install-gradle-wrapper.ps1  # Windows初期設定
├── install-gradle-wrapper.sh                     # Linux/macOS初期設定
├── README.md / README.ja.md
├── SECURITY.md / CHANGELOG.md / LICENSE
└── .gitignore
```

GitHubに載せるこの一式には、**実際の共有鍵・許可アドレス・運用URL・停電記録・APK署名鍵は含まれていません**。

## 1. WindowsでAPKを作成する

1. [Android Studio](https://developer.android.com/studio)をインストールします。今回の構成はAndroid SDK **API 36**、Gradle **8.13**、Android Gradle Plugin **8.13.2**、Kotlin **2.3.0**、JVMターゲット **17** です。
2. GitHubからソースZIPを取得して展開するか、リポジトリをクローンします。
3. `PowerLoggerAndroid` フォルダの **`SETUP-FIRST.bat`** を一度実行します。以前の `gradle-8.13-wrapper.jar` 取得URLは404になったため、修正版ではGradle公式GitHubのタグ `v8.13.0` から取得し、SHA-256 **`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`** と照合します。照合できないファイルは使用しません。
4. Android Studio → **Open** から、`settings.gradle.kts` がある `PowerLoggerAndroid` フォルダを開きます。
5. Gradle Syncを実行し、求められたらSDK Platform 36をインストールします。通常はAndroid Studio内蔵JDKを使います。
6. **Build → Build Bundle(s) / APK(s) → Build APK(s)** を実行します（メニューの名称はバージョンによって多少異なります）。
7. `app/build/outputs/apk/debug/app-debug.apk` をスマホへコピーして開き、自作APKのインストールを許可します。
8. アプリを開き、通知権限を許可して **「監視を開始」** を押します。

Linux/macOSの場合は `./install-gradle-wrapper.sh` を実行してからAndroid Studioで開くか、Android SDK設定済みで `./gradlew :app:assembleDebug` を実行できます。

**既存アプリを上書き更新するときは注意してください。** アプリID `jp.tsakailab.powerlogger` とSQLiteデータベースの構造は維持していますが、**以前のAPKと同じ署名鍵**で署名する必要があります。署名が異なると上書きインストールできません。履歴を残したい場合、安易に旧アプリをアンインストールしないでください。

## 2. 日本語・英語の切替

Android 13以降では、アプリ内の **「アプリの言語：English / 日本語」** からAndroidのアプリ別言語設定を開きます。**English** または **日本語** を選択してください。スマホ全体の言語を変更する必要はありません。画面・通知・CSV見出しが切り替わります。

なお、GASから送るメールの言語は `Code.gs` の `EMAIL_LANGUAGE`（`'ja'` または `'en'`）で設定します。Android側の表示言語とは独立です。

## 3. メール通知を設定する（任意）

通知OFFのままなら、Googleアカウントなしでも端末内の履歴記録を利用できます。

### 初めてGASを設定する場合

1. PCで <https://script.google.com/home/start> を開き、新しいプロジェクトを作ります。
2. GASエディタの `Code.gs` を、このリポジトリの **`server/Code.gs`** の全文で置き換えます。
3. **GASエディタ内で**、冒頭の許可リスト `ALLOWED_EMAILS` を編集します（公開GitHub側のファイルに実際のアドレスを書き込まないでください）。

   ```javascript
   const ALLOWED_EMAILS = [
     'one@example.com',
     'two@example.com'
   ];
   ```

   複数登録できます。実際に使用するアドレスに変更してください。重複や形式不正は拒否されます。
4. メール本文を英語にする場合は `EMAIL_LANGUAGE = 'en'` に変更します（初期値は `'ja'`）。必要なら `EMAIL_TIME_ZONE` も変更します。
5. 関数選択欄で **`setup`** を選び「実行」します。Googleの権限を承認し、**実行ログに表示される64文字の共有鍵**を安全に保管してください。`setup()` は既存の共有鍵を上書きしません。
6. 次に **`authorizeMail`** を実行し、メール送信権限を承認します。
7. **デプロイ → 新しいデプロイ → ウェブアプリ**。**次のユーザーとして実行：自分**、**アクセスできるユーザー：全員** にしてデプロイします。Google Workspaceの設定により全員を選べない場合はこの方法は使えません。
8. 発行された `https://script.google.com/macros/s/.../exec` のURLをコピーします（`/dev`ではありません）。
9. Androidの「停電ロガー」で、**許可リストにある1件のアドレス**・WebアプリURL・共有鍵を入力して保存し、メール通知をONにして「テストメールを送信」します。

### 以前のGASを更新する場合（v0.1.0〜v0.1.3）

1. 既存のGASプロジェクトの `Code.gs` を最新版と置き換えます。
2. `ALLOWED_EMAILS` に許可したいアドレスを列挙します。
3. **`setup()` は実行せず、`updateAllowedRecipients()` を実行します。** 共有鍵を維持したまま許可リストが更新されます。
4. **デプロイ → デプロイを管理 → 編集 → 新しいバージョン → デプロイ** で公開コードを更新します。既存の `/exec` URLを維持できます。
5. 同じアプリのメール設定、共有鍵、履歴を引き継げます。通知通信の署名形式（v1 HMAC）は変えていません。

### 許可リストの動作例

GASに `one@example.com` と `two@example.com` を登録した場合：

| アプリで指定した通知先 | 動作 |
|---|---|
| `one@example.com` | この1件だけに送信 |
| `two@example.com` | この1件だけに送信 |
| `other@example.com` | 送信拒否 `recipient_not_allowed` |

**許可リストの全員に一斉送信する機能ではありません。** アプリではメールアドレスを前後の空白除去・小文字化してから送信します。GAS側も登録時に同様に正規化します。

## 4. Android端末での動作確認

Android端末の「設定 → アプリ → 停電ロガー」などで、通知・バックグラウンドでの動作を許可し、可能ならバッテリー使用の制限を解除してください（項目名や利用可能な設定は端末・OSによって異なります）。常駐通知を停止しないでください。LTE SIMまたはバッテリー内蔵LTEモバイルWi-Fiを使用すれば、停電中も通信できる可能性があります。

1. **メールOFF**のまま監視開始、充電器を抜いて戻す。履歴とCSVを確認する。
2. **画面消灯状態**で数分間同じ試験をする。
3. GASを設定して**テストメール**を送信する。
4. モバイル通信を一時停止し、電源断・復旧を記録し、通信回復後の**遅延送信**を確認する。
5. 端末を**再起動**して監視が自動復帰するか確認する。
6. 日本語・英語に切り替えて表示とCSV見出しを確認する。

常駐監視はAndroidや端末メーカーの省電力制御により止められる可能性があり、完全な無停止監視は保証されません。再起動後や20秒ごとの状態確認で検出した場合、実際の停電時刻とずれるため画面上で区別します。監視停止中の復電時刻は不明です。

## 5. GitHub公開前チェックリスト

- [ ] **GASで実際に使用しているメールアドレスや共有鍵を、GitHub側のCode.gsに書き込まない。** 実運用の設定は自分のGASエディタだけで管理する。
- [ ] APK署名鍵、パスワード、Webアプリの運用URL、端末の履歴DB・CSV・ログをアップロードしない。
- [ ] 既存の個人設定・認証情報がないか、全ファイルを確認する。`.gitignore` だけでは防げない。
- [ ] 同梱のMIT Licenseの権利者表記を確認する（現在は `Power Logger contributors`）。
- [ ] 新しいGitHubリポジトリに**このフォルダの中身**を登録する。ZIPを1ファイル置くだけではソースコードの差分管理ができない。
- [ ] GitHubの **Actions** タブで **Verify Android and Apps Script** の実行結果を確認する。AndroidアプリとGASの模擬テストを自動実行する。
- [ ] 最終的に対象のAndroid端末でAPKのインストール、画面消灯・再起動・送信試験を完了してから安定版として扱う。

GASの模擬テスト（実メール送信なし）はNode.jsを使用します。

```sh
node test/server-tests.js
```

このテストはHMAC、複数許可アドレス、指定先1件への送信、許可外拒否、重複排除、鍵を維持した許可リスト更新、メールの日本語・英語表示を検証します。

## セキュリティ上の注意

[SECURITY.md](SECURITY.md)を参照してください。共有鍵はAndroidのアプリ専用設定領域に保存しますが、このバージョンではAndroid Keystoreによる暗号化は未実装です。GASのWebアプリは「全員」から呼び出せますが、HMAC署名と宛先制限で保護します。GASの送信制限や通信障害により通知が遅れたり届かなかったりすることがあります。
