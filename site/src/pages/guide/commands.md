---
layout: ../../layouts/GuideLayout.astro
title: 명령어
tab: commands
description: /shareteam 과 하위 명령 전부.
---

대부분은 **인벤토리(E) 왼쪽 위의 단추**로 열리는 팀 화면에서 할 수 있습니다. 명령으로도
그대로 됩니다.

**`/shareteam` 은 `/st` 로 줄여 쓸 수 있습니다.** 하위 명령은 모두 같고, 인자 없는 `/st` 도
팀 화면을 엽니다.

## /shareteam

| 명령 | 하는 일 |
|---|---|
| `/shareteam` | 팀 화면 열기 (모드가 있는 클라이언트) |
| `/shareteam help` | 도움말 |
| `/shareteam create <팀 이름>` | 팀 만들기. 친 사람이 리더가 됩니다 |
| `/shareteam invite <플레이어>` | 상대를 곧바로 팀에 넣습니다 (리더, **시작 전에만**) |
| `/shareteam start` | 무엇이 사라지는지 먼저 보여 줍니다 (리더) |
| `/shareteam start confirm` | 회차를 실제로 시작합니다. **되돌릴 수 없습니다** |
| `/shareteam status` | 지금 정해져 있는 설정 전부 |
| `/shareteam list` | 팀원 목록 |
| `/shareteam swap status` | 다음 위치 교환까지 남은 시간 |
| `/shareteam difficulty status` | 난이도가 지금 몇 %까지 올랐는지 |
| `/shareteam perk` · `perk list` | 지금 가진 증강 |
| `/shareteam storage` | 팀 창고를 엽니다 (`/창고` 로도 됩니다) |
| `/shareteam version` | 내 클라이언트와 서버가 같은 판인지 |
| `/shareteam version all` | 접속 중인 전원의 판 (운영자 전용) |
| `/shareteam leave` | 팀에서 나갑니다 |
| `/shareteam disband confirm` | 팀을 해체합니다 |

팀을 만들 때 함께 정하는 여덟 가지 설정은 [게임 시작](/sharedfate/guide/start)에 적어
두었습니다.

**팀 창고**는 인벤토리가 꽉 차서 못 받은 물건이 쌓이는 곳입니다. 흘린 줄 알았던 것이 거기에
있습니다.

## 서버를 되돌립니다

```text
/shareteam reset    (운영자 전용)
/yes  ·  /수락       30초 안에, 친 사람만
```

**서버를 통째로 처음 상태로 되돌립니다.** 회차 번호 · 팀 · 보유 증강 · 피해 기록 · 월드가
전부 사라지고 1회차로 돌아갑니다.

무엇이 지워지는지 먼저 보여 주고 기다립니다. 확정하면 **5초 뒤 서버가 종료되고 재시작 루프
스크립트가 새 월드로 다시 엽니다.** `/shareteam yes` · `/st 수락` 으로도 확정할 수 있습니다.

> ⚠ **재시작 루프 스크립트로 켠 서버에서만 쓰십시오.** `java -jar` 로 직접 띄운 서버는
> 꺼진 채로 돌아오지 않습니다. [설치](/sharedfate/guide/install)를 보십시오.

## 증강 시험 명령

<details>
  <summary><b>운영자 전용 — 실제로 노는 서버에서는 켜지 마십시오</b></summary>

증강을 하나하나 확인하려고 만든 명령입니다. 구간과 레벨을 건너뛰고 증강을 직접 넣고 뺍니다.

```text
/shareteam perktest status
/shareteam perktest owner              「고른 사람만」 걸리는 증강의 지금 주인
/shareteam perktest give <증강id>       (증강 id 는 자동 완성됩니다)
/shareteam perktest remove <증강id>
/shareteam perktest clear confirm
/shareteam perktest list all
```

**잠금이 둘이고 둘 다 있어야 동작합니다.**

1. `config/sharedfate.json` 의 `perkTestCommands` 가 `true`
2. 명령을 치는 사람이 **운영자(권한 4)**

`perkTestCommands` 의 기본값은 `false` 입니다. 켜져 있으면 서버가 뜰 때 경고 로그가 남고
접속하는 사람마다 채팅으로 경고를 받습니다. 한 번이라도 쓴 팀은 `/shareteam status` 에
**「정상 회차가 아닙니다」** 가 표시되며, 그 표식은 회차가 끝날 때까지 지워지지 않습니다.

즉시 지급이나 「유산」의 몰수처럼 **고르는 순간에만 일어나는 효과는 재현되지 않습니다.**
덕분에 넣었다 뺐다를 반복해도 아이템이 불어나지 않습니다.

</details>
