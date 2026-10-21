import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { PERKS_JSON } from '../src/lib/paths';
import { RARITY_NAME, countByRarity, loadPerks } from '../src/lib/perks';

const raw = JSON.parse(readFileSync(PERKS_JSON, 'utf-8'));
const perks = loadPerks();

describe('증강 읽기', () => {
  it('정의 파일과 개수가 같다', () => {
    expect(perks.length).toBe(raw.perks.length);
  });

  it('모든 증강에 id·이름·설명이 있다', () => {
    for (const perk of perks) {
      expect(perk.id).toBeTruthy();
      expect(perk.name.length).toBeGreaterThan(0);
      expect(perk.description.length).toBeGreaterThan(0);
    }
  });

  it('등급은 셋 + 드래곤전 대비뿐이다', () => {
    const allowed = new Set(['silver', 'gold', 'prism', 'trial']);
    for (const perk of perks) {
      expect(allowed.has(perk.rarity)).toBe(true);
    }
  });

  it('세 등급이 모두 하나 이상 있다', () => {
    for (const rarity of ['silver', 'gold', 'prism'] as const) {
      expect(perks.filter((p) => p.rarity === rarity).length).toBeGreaterThan(0);
    }
  });

  it('setTypes 는 언제나 배열이다 — 유형 없는 증강은 빈 배열', () => {
    for (const perk of perks) {
      expect(Array.isArray(perk.setTypes)).toBe(true);
    }
    expect(perks.some((p) => p.setTypes.length === 0)).toBe(true);
  });

  it('등급 차례로 정렬돼 있다 — 실버 → 골드 → 프리즘 → 드래곤전 대비', () => {
    const order = { silver: 0, gold: 1, prism: 2, trial: 3 } as const;
    const seen = perks.map((p) => order[p.rarity]);
    expect(seen).toEqual([...seen].sort((a, b) => a - b));
  });

  it('등급별 개수의 합이 전체와 같다', () => {
    const counts = countByRarity(perks);
    expect(counts.silver + counts.gold + counts.prism + counts.trial).toBe(perks.length);
  });

  it('드래곤전 대비의 화면 이름이 게임과 같다', () => {
    expect(RARITY_NAME.trial).toBe('드래곤전 대비');
  });

  // 정의 파일에 드래곤전 대비가 아직 없어도 통과한다 — 있으면 그 증강들만 본다.
  it('드래곤전 대비 증강은 세트 유형이 없다', () => {
    for (const perk of perks.filter((p) => p.rarity === 'trial')) {
      expect(perk.setTypes).toEqual([]);
    }
  });

  it('가중치는 정의 파일 값이고, 없으면 1 이다', () => {
    const rawById = new Map<string, { weight?: number }>(raw.perks.map((p: { id: string }) => [p.id, p]));
    for (const perk of perks) {
      expect(perk.weight).toBe(rawById.get(perk.id)?.weight ?? 1);
      expect(perk.weight).toBeGreaterThan(0);
    }
  });
});

describe('드래곤전 대비 색', () => {
  const tokens = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf-8');
  const card = readFileSync(new URL('../src/components/PerkCard.astro', import.meta.url), 'utf-8');

  it('엔더 연보라 #C79BFF 를 토큰으로 둔다 — 게임 카드와 같은 값', () => {
    expect(tokens).toMatch(/--trial:\s*#C79BFF;/i);
  });

  it('카드가 trial 일 때 그 토큰을 쓴다', () => {
    expect(card).toMatch(/\.perk\[data-rarity='trial'\]\s*\{\s*--rarity:\s*var\(--trial\);/);
  });
});
