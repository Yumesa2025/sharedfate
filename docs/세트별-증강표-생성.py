"""세트별 증강표를 정의 파일에서 그대로 뽑는다. 손으로 옮겨 적은 표는 반드시 낡는다.

저장소 어디서 실행해도 되게 경로를 이 파일 위치에서 구한다. 절대 경로를 박아 두면 남의
작업 환경에서 그대로 깨진다.

    python docs/세트별-증강표-생성.py
"""
import io
import json
import os
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RESOURCES = os.path.join(ROOT, "src", "main", "resources")
PERKS = os.path.join(RESOURCES, "sharedfate-perks-default.json")
SETS = os.path.join(RESOURCES, "sharedfate-sets-default.json")
OUT = os.path.join(HERE, "세트별-증강표.md")

# PerkSetType.java 의 선언 순서·이름·임계값 그대로다. 바뀌면 여기도 바꿔야 한다.
TYPES = OrderedDict([
    ("weapon", ("무기", 2)),
    ("power", ("화력", 3)),
    ("hunt", ("사냥", 2)),
    ("mining", ("채굴", 3)),
    ("supply", ("보급", 2)),
    ("defense", ("방어", 2)),
    ("survival", ("생존", 2)),
    ("recovery", ("회복", 2)),
    ("swap", ("교환", 2)),
    ("gamble", ("도박", 2)),
    ("mobility", ("기동", 2)),
    ("blessing", ("가호", 2)),
    ("pioneer", ("개척", 2)),
    ("bond", ("결속", 2)),
])

RARITY = {"silver": "실버", "gold": "골드", "prism": "프리즘"}
RARITY_ORDER = {"silver": 0, "gold": 1, "prism": 2}

perks = json.load(io.open(PERKS, encoding="utf-8"))["perks"]
sets = {s["type"]: s["tiers"] for s in json.load(io.open(SETS, encoding="utf-8"))["sets"]}

by_type = {key: [] for key in TYPES}
typeless = []
for perk in perks:
    types = perk.get("set_types") or []
    if not types:
        typeless.append(perk)
    for t in types:
        by_type.setdefault(t, []).append(perk)


def sort_key(perk):
    return (RARITY_ORDER.get(perk["rarity"], 9), perk["name"])


def cell(text):
    # 표 안에서 | 는 칸을 가르므로 반드시 막는다.
    return (text or "").replace("|", "\\|").replace("\n", " ").strip()


def rarity_of(perk):
    return RARITY.get(perk["rarity"], perk["rarity"])


lines = []
w = lines.append

w("# 세트별 증강표")
w("")
w("정의 파일에서 그대로 뽑은 표입니다. **손으로 고치지 마십시오** — 다시 뽑으면 덮어써집니다.")
w("증강을 고쳤으면 `src/main/resources/` 의 정의를 고친 뒤 이렇게 다시 뽑습니다.")
w("")
w("```")
w("python docs/세트별-증강표-생성.py")
w("```")
w("")
w("> **증강을 고쳤는데 이 표를 다시 안 뽑으면 그대로 낡습니다.** 표와 정의가 어긋나면 표를")
w("> 보고 잘못 판단하게 되므로, 증강·세트 정의를 건드린 판에서는 반드시 함께 뽑으십시오.")
w("")

total = len(perks)
counts = {r: sum(1 for p in perks if p["rarity"] == r) for r in RARITY}
w("모두 **%d개**입니다 — 실버 %d · 골드 %d · 프리즘 %d."
  % (total, counts["silver"], counts["gold"], counts["prism"]))
w("")

# ------------------------------------------------------------------ 한눈에
w("## 한눈에")
w("")
w("| 유형 | 임계 | 가진 증강 | 실버 | 골드 | 프리즘 | 단계 |")
w("|---|--:|--:|--:|--:|--:|---|")
for key, (name, threshold) in TYPES.items():
    group = by_type.get(key, [])
    tiers = sets.get(key, [])
    tier_text = " · ".join(str(t["count"]) for t in tiers) if tiers else "—"
    w("| **%s** | %d | %d | %d | %d | %d | %s |" % (
        name, threshold, len(group),
        sum(1 for p in group if p["rarity"] == "silver"),
        sum(1 for p in group if p["rarity"] == "gold"),
        sum(1 for p in group if p["rarity"] == "prism"),
        tier_text))
w("| 유형 없음 | — | %d | %d | %d | %d | — |" % (
    len(typeless),
    sum(1 for p in typeless if p["rarity"] == "silver"),
    sum(1 for p in typeless if p["rarity"] == "gold"),
    sum(1 for p in typeless if p["rarity"] == "prism")))
w("")
w("**임계**는 세트가 열리기 시작하는 개수, **단계**는 실제로 정의된 단계들입니다.")
w("한 증강이 유형을 둘 가질 수 있어 「가진 증강」의 합은 %d보다 큽니다." % total)
w("")

dual = [p for p in perks if len(p.get("set_types") or []) >= 2]
if dual:
    w("유형을 **둘** 가진 증강은 %d개입니다 — %s." % (
        len(dual),
        ", ".join("%s(%s)" % (p["name"], " · ".join(TYPES[t][0] for t in p["set_types"]))
                  for p in sorted(dual, key=sort_key))))
    w("")

# ------------------------------------------------------------------ 유형별
w("---")
w("")
for key, (name, threshold) in TYPES.items():
    group = sorted(by_type.get(key, []), key=sort_key)
    tiers = sets.get(key, [])
    w("## %s — %d개" % (name, len(group)))
    w("")
    if tiers:
        w("| 단계 | 필요 | 효과 |")
        w("|---|--:|---|")
        for tier in tiers:
            w("| %s | %d개 | %s |" % (
                cell(tier.get("name") or "%s %d" % (name, tier["count"])),
                tier["count"],
                cell(tier.get("description")) or "—"))
        w("")
    reachable = max((t["count"] for t in tiers), default=0)
    if reachable and len(group) < reachable:
        w("> ⚠ **마지막 단계에 닿을 수 없습니다.** %d개가 필요한데 이 유형의 증강이 %d개뿐입니다."
          % (reachable, len(group)))
        w("")
    w("| 이름 | 등급 | 설명 |")
    w("|---|---|---|")
    for perk in group:
        others = [TYPES[t][0] for t in perk["set_types"] if t != key]
        suffix = " *(%s 겸업)*" % " · ".join(others) if others else ""
        w("| %s%s | %s | %s |" % (
            cell(perk["name"]), suffix, rarity_of(perk), cell(perk.get("description"))))
    w("")

# ------------------------------------------------------------------ 무유형
w("---")
w("")
w("## 유형 없음 — %d개" % len(typeless))
w("")
w("세트에 들어가지 않는 증강입니다. 세트를 노리는 판에서는 **세트 진행을 못 밀어 줍니다.**")
w("")
w("| 이름 | 등급 | 설명 |")
w("|---|---|---|")
for perk in sorted(typeless, key=sort_key):
    w("| %s | %s | %s |" % (cell(perk["name"]), rarity_of(perk), cell(perk.get("description"))))
w("")

io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
print("wrote", len(lines), "lines")
