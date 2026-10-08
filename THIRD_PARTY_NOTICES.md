# Third-party notices

このアプリは以下のソフトウェアとデータを利用しています。ライセンス全文は `app/src/main/assets/licenses/` にあり、アプリ内でも表示できます。

| 名前 | 用途 | ライセンス |
|---|---|---|
| [jump3r](https://codeberg.org/sciss/jump3r) 1.0.5(LAME 3.98.4 の Java 移植) | MP3 エンコード | LGPL v2.1 以降 |
| Kotlin / kotlinx.coroutines | 言語・非同期処理 | Apache License 2.0 |
| AndroidX(Core, Activity, Lifecycle, DataStore, DocumentFile) | Android の基盤 | Apache License 2.0 |
| Jetpack Compose / Material 3 / Material Icons | 画面 | Apache License 2.0 |
| [MusicBrainz](https://musicbrainz.org) | 曲情報データ | CC0 1.0(コアデータ) |
| [Cover Art Archive](https://coverartarchive.org) | ジャケット画像 | 各画像の権利者による |

## jump3r(LGPL)について

jump3r は改変せず、独立したライブラリ(Maven: `de.sciss:jump3r:1.0.5`)として利用しています。ソースコードは上記リポジトリと Maven Central から入手できます。LGPL に基づき、このプロジェクトを使ってライブラリを差し替えたものをビルドできます。

## 本アプリ独自の実装

FLAC エンコーダ、MP4・ID3 タグ処理、USB 光学ドライブ制御(SCSI/MMC)、`javax.sound.sampled` 互換クラスは本アプリ独自の実装で、本アプリと同じ GPL v3 で提供します。AAC のエンコードと曲の再生には Android OS 内蔵のコーデックを使用しています。
