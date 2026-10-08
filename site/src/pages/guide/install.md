---
layout: ../../layouts/GuideLayout.astro
title: 설치
tab: install
description: SharedFate 를 클라이언트와 서버에 넣는 법.
---

받는 파일은 셋이고, 셋 다 받을 필요는 없습니다.

| 파일 | 누가 받나 |
|---|---|
| `SharedFate-<판>-client.zip` | **그냥 플레이하는 사람.** 모드와 호환 Fabric API, 안내문이 들어 있습니다 |
| `SharedFate-<판>-server.zip` | **서버를 여는 사람.** 위의 둘에 **재시작 루프 스크립트**와 기본 설정이 더 들어 있습니다 |
| `sharedfate-<판>.jar` | 직접 챙겨 넣을 사람 |

## 필요한 것

| | |
|---|---|
| Minecraft | Java Edition **26.3** |
| Fabric Loader | **0.19.5** 이상 |
| Fabric API | **0.161.0+26.3** 이상 |
| Java | **25** 이상 |
| 서버 메모리 | 4인 기준 **4GB** 권장 |

> ⚠ **마인크래프트 26.3 전용입니다. 26.2 에서는 아예 켜지지 않습니다.**
> 서버와 모든 클라이언트가 **마인크래프트 자체를 26.3 으로** 올려야 합니다.
> SharedFate 판을 서로 맞추더라도 **마인크래프트 판이 다르면** 함께 쓸 수 없습니다.

> **서버와 모든 클라이언트는 SharedFate 판이 같아야 합니다.** 통신 규약이 다르면 접속하는
> 순간 거부됩니다. 이상하게 동작하는 대신 바로 알 수 있게 한 것입니다.
> 게임 안에서 `/shareteam version` 으로 확인할 수 있습니다.

## 클라이언트

1. Minecraft **26.3** 과 Fabric Loader **0.19.5 이상**을 설치합니다.
2. **게임과 런처를 완전히 종료합니다.** 켜 둔 채로 JAR 를 바꾸면 새 기능을 누르는 순간 죽습니다.
3. 클라이언트 ZIP 을 풉니다.
4. `%appdata%\.minecraft\mods` 에서 **이전 `sharedfate-*.jar` 를 지웁니다.**
5. ZIP 의 `mods` 폴더에 든 JAR **두 개**를 그 자리에 복사합니다.
6. Fabric 프로필로 게임을 실행해 같은 판의 서버에 접속합니다.

> ⚠ **26.2 에서 올라오는 경우 26.2 용 Fabric API(`fabric-api-*+26.2.jar`)도 함께 빼십시오.**
> 새로 넣는 `fabric-api-0.161.0+26.3.jar` 는 **이름이 달라 덮어써지지 않습니다.** 둘이 함께
> 남으면 Fabric API 중복 로드로 게임이 아예 켜지지 않습니다.

## 서버

바닐라 서버를 운영해 본 적이 없어도 그대로 따라 하면 됩니다.

### 1. 서버 폴더 만들기

빈 폴더를 하나 만들고 [Fabric Installer](https://fabricmc.net/use/server/) 로 **Minecraft
26.3 / Loader 0.19.5 이상** 서버를 만듭니다. 창으로 만들 때 **서버 JAR 를 받는 항목을
켜십시오.** 명령줄이 편하면 이렇게 합니다.

```
java -jar fabric-installer.jar server -mcversion 26.3 -loader 0.19.5 -downloadMinecraft
```

> ⚠ **`-downloadMinecraft` 를 빼면 안 됩니다.** 빼면 `fabric-server-launch.jar` 만 생기는데
> 그것은 600바이트짜리 껍데기입니다. 마인크래프트 본체(`server.jar`, 60MB 쯤)가 같은 폴더에
> 있어야 서버가 켜집니다.

`eula.txt` 는 만들지 않아도 됩니다. 재시작 스크립트가 처음 켤 때 한 번 물어보고 만듭니다.

### 2. 모드 넣기

```
서버폴더/
  fabric-server-launch.jar
  server.jar
  server.properties
  mods/
    sharedfate-<판>.jar
    fabric-api-0.161.0+26.3.jar
```

서버 ZIP 의 `server.properties` 는 바닐라 기본값에서 셋만 손본 것입니다 — `hardcore=true`(전멸
시 「게임 오버」), `white-list=false`(26.3 기본값은 `true` 라 그대로면 아무도 못 들어옵니다),
`motd=`(모드가 채웁니다).

> ⚠ **`hardcore` 는 월드가 만들어질 때 `level.dat` 에 박히고 그 뒤로는 이 파일을 보지 않습니다.**
> 이미 만든 월드를 바꾸려면 `world` 를 지우거나 다음 회차를 기다려야 합니다.
>
> ⚠ **이미 돌고 있는 서버에는 이 파일을 풀지 마십시오.** 포트·시드 같은 값이 날아갑니다.

서버 ZIP 을 받았다면 이 구조가 이미 들어 있습니다.

> ⚠ **압축 프로그램이 `SharedFate-<판>-server` 폴더를 하나 더 만드는 것을 조심하십시오.**
> `mods` 와 `config`, 켜는 파일이 **서버 루트에 바로** 있어야 합니다. 폴더 안에 들어갔으면
> 안의 것들을 밖으로 끌어내십시오.

> ⚠ **26.2 에서 올라오는 서버라면 `mods` 의 옛 JAR 두 개를 먼저 빼십시오.** 클라이언트와
> 같은 이유입니다.

### 3. 재시작 루프로 켭니다 — 이 단계를 건너뛰면 안 됩니다

팀이 전멸하면 **다음 회차의 새 월드가 시작됩니다.** 그런데 모드는 월드를 직접 지우지
않습니다. 서버 루트에 표식 파일을 하나 남기고 **정상 종료할 뿐**이고, 표식에 적힌 월드
폴더를 지우고 서버를 다시 켜는 일은 **재시작 루프 스크립트**가 합니다.

> ⚠ **`java -jar` 로 직접 띄우면 안 됩니다.** 전멸한 순간 서버가 꺼진 채로 남습니다.
> 이걸 모르면 처음 여는 사람은 반드시 막힙니다.

스크립트는 서버 ZIP 에 들어 있습니다. 서버 폴더에 복사하십시오.

| 운영체제 | 켜는 파일 | 실제 일을 하는 파일 |
|---|---|---|
| Windows | `start-sharedfate-server.bat` | `sharedfate-server-loop.ps1` |
| Linux · macOS | `start-sharedfate-server.sh` | `sharedfate-server-loop.sh` |

**Windows**

```bat
start-sharedfate-server.bat
```

**Linux · macOS**

```bash
chmod +x start-sharedfate-server.sh sharedfate-server-loop.sh
./start-sharedfate-server.sh
```

처음 켜면 **Minecraft EULA 동의를 한 번 묻습니다.** 링크를 읽고 `y` 를 입력하면 `eula.txt` 가
만들어지고 서버가 이어서 켜집니다. 다음부터는 묻지 않습니다.

`eula=true` 는 설정값이 아니라 **동의 서명**이라 배포 ZIP 에 미리 넣어 둘 수 없습니다. 창 없이
돌리는 곳(작업 스케줄러 · systemd · 호스팅 패널)에서는 물어볼 수 없으므로 Windows 는
`-AcceptEula`, Linux · macOS 는 `--accept-eula` 로 미리 넘기십시오.

메모리는 켜는 파일 안의 `-MinMemory`·`-MaxMemory`(Windows) 또는 `--min`·`--max`(Linux)
값을 고쳐서 정합니다.

### 월드는 정확히 하나만, 검증한 뒤에 지웁니다

루프 스크립트는 표식에 적힌 경로가 아래를 **모두** 만족할 때만 지웁니다. 하나라도 어긋나면
아무것도 지우지 않고 멈춥니다.

- 표식 파일이 **서버 루트**에 있고, 정해진 머리글로 시작하는 **두 줄**일 것
- 월드 경로가 **절대 경로**일 것
- 그 폴더의 **부모가 정확히 서버 루트**일 것 (두 단계 아래도 안 됩니다)
- 서버 루트 자체가 아닐 것
- **심볼릭 링크·정션이 아닐** 것

그래도 **운영 중인 월드는 먼저 백업하십시오.**

## 켜고 나서

서버를 한 번 켜면 `config/sharedfate.json` 하나가 생깁니다. 손대지 않아도 그대로 놀 수
있고, 바꿀 만한 것은 넷입니다.

- **`requireClientMod`** (기본 켬) — 모드가 없는 클라이언트의 접속을 막습니다. 끄면 공유는
  되지만 화면 표시가 전혀 없어 무엇이 일어나는지 알 수 없습니다
- **`overrideServerMotd`** (기본 켬) — 서버 목록에 뜨는 설명을 채워 줍니다. 직접 쓴 설명이
  있으면 덮어쓰지 않습니다
- **`perkTestCommands`** (기본 끔) — 증강을 직접 넣고 빼는 운영자 전용 시험 명령입니다.
  **실제로 노는 서버에서는 켜지 마십시오**
- **`dragonHealthPerMember`** (기본 600) — 팀 인원 한 명당 엔더 드래곤 최대 체력입니다.
  바닐라는 200 입니다. 팀 설정에서 「드래곤 시련」을 끈 팀에게는 읽히지 않습니다.
  **0 으로 두면 서버 전체에서 드래곤 강화·시련·엔드 전원 소환이 함께 꺼집니다**

**증강과 세트 정의는 모드 안에 들어 있습니다.** 설정 폴더로 나오지 않으므로 **판을 올릴 때
지울 것이 없습니다.** 예전 판에서 올라와 `config/sharedfate-perks.json` 이 남아 있어도
읽지 않으니 그대로 두어도 됩니다.

> **서버가 켜져 있는 동안 `mods` 의 JAR 를 바꾸지 마십시오.** Fabric 은 클래스를 필요할
> 때마다 JAR 에서 읽으므로, 실행 중에 파일이 바뀌면 아직 읽지 않은 클래스를 찾는 순간
> 죽습니다.
>
> **서버 창을 닫아서 내리지 마십시오.** 종료 기록이 남지 않아 나중에 원인을 찾기 어렵습니다.
> 콘솔에 `stop` 을 치십시오.

여기까지 왔으면 [게임 시작](/sharedfate/guide/start)으로 갑니다.

## 업데이트와 제거

- **업데이트** — 게임과 서버를 **끄고** 이전 SharedFate JAR 를 새 JAR 로 바꿉니다. 여러 판을
  동시에 두지 마십시오. 서버와 모든 클라이언트를 함께 갱신해야 합니다
- **제거** — 서버를 정상 종료하고 월드를 백업한 뒤 서버와 모든 클라이언트에서 JAR 를
  지웁니다. 공유 상태가 들어 있는 운영 월드를 바닐라로 바로 열지 않는 편이 좋습니다

백업할 것은 넷입니다. **팀 명단은 월드와 별개라서**, `world/` 만 지우면 팀이 그대로
살아남습니다.

| 무엇 | 파일 |
|---|---|
| 월드 | `world/` |
| 회차 번호 | `sharedfate-run-state.json` |
| 피해 기록 | `sharedfate-damage-history.json` |
| 팀 명단 | `sharedfate-team-roster.json` |
