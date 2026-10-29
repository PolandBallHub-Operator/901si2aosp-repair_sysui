# 901SI UI復元プロジェクト (901si2aosp-repair_sysui)
## 概要

本プロジェクトは、Docomoのキッズケータイ「901SI」のシステムを解析し、独自UIやシステム制限（SH-03Mとの違いなど）を排除・修正し、AOSPベースの使いやすいUIへと復元（Lsposed等を使用）することを目的としています。

---

## 解析対象
- /system/framework のjar
- /system/framework/freamwork-res.apk
- /system/priv-app/systemui.apk
- logcat,dumpsys,いろいろ

## 開発状況

###  できてること (Done)
- **901SI in Back to Aosp Lsposed**: Lsposed等を用いたAOSP化へのアプローチ
- **Androidの電源メニュー出す**: 電源ボタン長押し時のメニュー（「でんげんをきります」「さいきどう」など）の正常表示化
- **QSを出す**: クイックセッティング（QS）パネル自体の呼び出し・表示
- **ナビバーを出す**: AOSPナビバーを強制表示化
- 

###  直すバグ (Todo / Bugs)
- **QSがはみ出す（notificationsのみ）**: クイックセッティングの表示領域が崩れ、通知エリアのみがはみ出てしまう問題の修正 **苦戦!** 
- **QSがタッチできない**: 表示されたQSパネルが操作を受け付けないバグの修正 **苦戦!**


###  次にしたいこと (Next Actions)
- AOSPlockscreenの復元
- settingのaosp化

---

## ハードウェア・システムの特徴（メモより）
- Docomo SH-03Mとちがいuiが力業で封印されてる。
